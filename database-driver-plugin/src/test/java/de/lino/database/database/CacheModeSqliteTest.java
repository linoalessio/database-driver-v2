package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL backend's storage primitives exercised through every {@link CacheMode}, against
 * SQLite - the one SQL vendor that needs no external server. The memory-versus-store
 * distinctions are proven in {@link CacheModeJsonStoreTest} where the store is directly
 * observable; here the point is that the shared SQL primitives (streaming load, point
 * read/check, native count, the write statements) behave correctly underneath each mode.
 */
class CacheModeSqliteTest {

    @TempDir
    Path root;

    DatabaseRepositoryRegistry registry;
    DatabaseProvider provider;

    @BeforeEach
    void setUp() {
        this.registry = new DatabaseRepositoryRegistry(false);
        this.provider = this.registry.registerDatabaseProvider(1, DatabaseType.SQLITE,
                new Credentials(this.root.resolve("config.json"), "", "", "", -1, "", this.root.resolve("db")));
    }

    @AfterEach
    void tearDown() {
        this.registry.shutdown();
    }

    private static DatabaseEntry entry(String id, String value) {
        return new DatabaseEntry(id, new JsonDocument("data", new JsonDocument("v", value)));
    }

    private void roundTrip(DatabaseSection section) {

        section.insert(entry("a", "1"));
        section.insert(entry("b", "2"));

        assertEquals(2, section.count());
        assertTrue(section.exists("a"));
        assertFalse(section.exists("ghost"));
        assertEquals("1", section.findEntryById("a").orElseThrow().getMetaData().getString("v"));
        assertTrue(section.findEntryById("ghost").isEmpty());
        assertEquals(2, section.getEntries().size());

        assertThrows(DataAlreadyExist.class, () -> section.insert(entry("a", "x")));
        assertThrows(NoSuchEntryFound.class, () -> section.update(entry("ghost", "x")));
        assertThrows(NoSuchEntryFound.class, () -> section.delete("ghost"));

        section.update(entry("a", "1'"));
        assertEquals("1'", section.findEntryById("a").orElseThrow().getMetaData().getString("v"));

        section.delete("b");
        assertFalse(section.exists("b"));
        assertEquals(1, section.count());

    }

    @Test
    void fullModeRoundTrip() {
        this.roundTrip(this.provider.createSection("t_full"));
    }

    @Test
    void lazyModeRoundTrip() {
        this.roundTrip(this.provider.createSection("t_lazy", SectionConfig.lazy()));
    }

    @Test
    void boundedModeRoundTrip() {
        this.roundTrip(this.provider.createSection("t_bounded", SectionConfig.bounded(1)));
    }

    @Test
    void noneModeRoundTrip() {
        this.roundTrip(this.provider.createSection("t_none", SectionConfig.none()));
    }

    @Test
    void tablesSurviveAndRediscoverAcrossProviderReload() {

        final DatabaseSection section = this.provider.createSection("persistent");
        section.insert(entry("x", "42"));

        this.provider.reload();

        assertTrue(this.provider.existsSection("persistent"), "the table is rediscovered by name");
        assertEquals("42", this.provider.getSection("persistent").orElseThrow()
                .findEntryById("x").orElseThrow().getMetaData().getString("v"));

    }

    @Test
    void boundedReadThroughSeesRowsWrittenBeforeItsCreation() {

        // Rows written under one configuration must be reachable after the section is
        // re-declared under another - the cache is a view, never the data.
        final DatabaseSection full = this.provider.createSection("migrating");
        for (int i = 0; i < 10; i++) full.insert(entry("r" + i, Integer.toString(i)));

        final DatabaseSection bounded = this.provider.createSection("migrating", SectionConfig.bounded(3));
        assertEquals(10, bounded.count());
        for (int i = 0; i < 10; i++) {
            assertEquals(Integer.toString(i), bounded.findEntryById("r" + i).orElseThrow().getMetaData().getString("v"));
        }

    }

}
