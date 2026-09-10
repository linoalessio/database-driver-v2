package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.nosql.json.JsonDatabaseProvider;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatabaseSection#forEachEntry} and {@link DatabaseSection#getEntries(long, int)}
 * across cache modes and backends. The load-bearing property is the paging contract: pages are
 * ordered by id and identical for identical arguments <em>regardless of mode or backend</em> -
 * whether they come from a sorted in-memory view (FULL/LAZY), the engine's stream-and-skip
 * window (BOUNDED/NONE on file stores), or a native ORDER BY/LIMIT pushdown (SQL).
 */
class StreamingAndPagingTest {

    private static final int ENTRIES = 25;

    @TempDir
    java.nio.file.Path root;

    DatabaseRepositoryRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = new DatabaseRepositoryRegistry(false);
    }

    @AfterEach
    void tearDown() {
        this.registry.shutdown();
    }

    private DatabaseProvider jsonProvider() {
        return new JsonDatabaseProvider(new Credentials(this.root.resolve("config-json.json"), this.root.resolve("repo")));
    }

    private DatabaseProvider sqliteProvider() {
        return this.registry.registerDatabaseProvider(1, DatabaseType.SQLITE,
                new Credentials(this.root.resolve("config-sqlite.json"), "", "", "", -1, "", this.root.resolve("db")));
    }

    /** Inserts ids e00 .. e24 in shuffled order, so id order is not insertion order. */
    private static void fill(DatabaseSection section) {
        for (int i = 0; i < ENTRIES; i++) {
            final int id = (i * 7) % ENTRIES; // visits every value 0..24 exactly once
            section.insert(new DatabaseEntry(String.format("e%02d", id),
                    new JsonDocument("data", new JsonDocument("n", Integer.toString(id)))));
        }
    }

    private static void assertPagingContract(DatabaseSection section) {

        // Chunked reads reassemble to the complete, id-sorted section - no entry lost,
        // duplicated or misordered at any page boundary.
        final List<String> collected = new ArrayList<>();
        for (long offset = 0; ; offset += 10) {
            final List<DatabaseEntry> page = section.getEntries(offset, 10);
            page.forEach(entry -> collected.add(entry.getId()));
            if (page.size() < 10) break;
        }

        assertEquals(ENTRIES, collected.size());
        for (int i = 0; i < ENTRIES; i++) assertEquals(String.format("e%02d", i), collected.get(i));

        // Interior page, straddling nothing special.
        final List<DatabaseEntry> page = section.getEntries(7, 3);
        assertEquals(List.of("e07", "e08", "e09"), page.stream().map(DatabaseEntry::getId).toList());

        // Edges: beyond the end, exactly the end, an empty page, invalid arguments.
        assertTrue(section.getEntries(ENTRIES, 10).isEmpty());
        assertEquals(1, section.getEntries(ENTRIES - 1, 10).size());
        assertTrue(section.getEntries(0, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> section.getEntries(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> section.getEntries(0, -5));

    }

    private static void assertStreamsEverything(DatabaseSection section) {

        final AtomicInteger seen = new AtomicInteger();
        section.forEachEntry(entry -> seen.incrementAndGet());
        assertEquals(ENTRIES, seen.get());

    }

    @Test
    void jsonStorePagesIdenticallyInEveryMode() {

        final DatabaseProvider provider = this.jsonProvider();

        // One shared dataset, read through four differently-configured sections.
        fill(provider.createSection("paged"));

        for (final SectionConfig config : List.of(SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(5), SectionConfig.none())) {
            final DatabaseSection section = provider.createSection("paged", config);
            assertPagingContract(section);
            assertStreamsEverything(section);
        }

    }

    @Test
    void sqlitePagesIdenticallyInEveryMode() {

        final DatabaseProvider provider = this.sqliteProvider();

        fill(provider.createSection("paged"));

        for (final SectionConfig config : List.of(SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(5), SectionConfig.none())) {
            final DatabaseSection section = provider.createSection("paged", config);
            assertPagingContract(section);   // BOUNDED/NONE exercise the native ORDER BY pushdown
            assertStreamsEverything(section); // NONE exercises the fetch-size streaming loadAll
        }

    }

    @Test
    void streamingSeesTheStoreNotTheCacheInNoneMode() {

        final DatabaseProvider provider = this.jsonProvider();
        final DatabaseSection section = provider.createSection("live", SectionConfig.none());

        fill(section);

        // An entry added behind the section's back is streamed on the very next pass -
        // NONE-mode enumeration reads the store, not any memory view.
        new JsonDocument().append("id", "external").append("data", new JsonDocument("n", "-1"))
                .write(this.root.resolve("repo").resolve("live").resolve("external.json"));

        final AtomicInteger seen = new AtomicInteger();
        section.forEachEntry(entry -> seen.incrementAndGet());
        assertEquals(ENTRIES + 1, seen.get());

    }

}
