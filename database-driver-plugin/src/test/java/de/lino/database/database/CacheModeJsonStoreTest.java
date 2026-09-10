package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link CacheMode} exercised against the JSON file store - the one backend that needs no
 * external server, whose backing state (one file per entry) is also directly observable from
 * the test, which is what lets these tests distinguish "answered from memory" from "answered
 * from the store" instead of only checking results.
 */
class CacheModeJsonStoreTest {

    @TempDir
    Path root;

    DatabaseProvider provider;

    @BeforeEach
    void setUp() {
        new DatabaseRepositoryRegistry(false);
        this.provider = new de.lino.database.database.nosql.json.JsonDatabaseProvider(
                new Credentials(this.root.resolve("config.json"), this.root.resolve("repo")));
    }

    private static DatabaseEntry entry(String id, String key, String value) {
        return new DatabaseEntry(id, new JsonDocument("data", new JsonDocument(key, value)));
    }

    private Path entryFile(String section, String id) {
        return this.root.resolve("repo").resolve(section).resolve(id + ".json");
    }

    private void writeExternalEntry(String section, String id) {
        new JsonDocument().append("id", id).append("data", new JsonDocument("external", "yes"))
                .write(this.entryFile(section, id));
    }

    // ------------------------------------------------------------------ FULL

    @Test
    void fullSectionKeepsHistoricalSemantics() {

        final DatabaseSection section = this.provider.createSection("players");

        section.insert(entry("p1", "name", "alice"));
        section.insert(entry("p2", "name", "bob"));

        assertEquals(2, section.count());
        assertTrue(section.exists("p1"));
        assertEquals("alice", section.findEntryById("p1").orElseThrow().getMetaData().getString("name"));
        assertEquals(2, section.getEntries().size());

        assertThrows(DataAlreadyExist.class, () -> section.insert(entry("p1", "x", "y")));
        assertThrows(NoSuchEntryFound.class, () -> section.update(entry("ghost", "x", "y")));
        assertThrows(NoSuchEntryFound.class, () -> section.delete("ghost"));

        section.update(entry("p1", "city", "berlin"));
        assertTrue(JsonDocument.load(this.entryFile("players", "p1")).getMetaData("data").contains("city"),
                "write must reach the backing store");

        section.delete("p2");
        assertFalse(section.exists("p2"));
        assertTrue(Files.notExists(this.entryFile("players", "p2")));

        section.clear();
        assertEquals(0, section.count());

    }

    @Test
    void fullSectionIsWarmAtCreationAndReadsFromMemory() {

        final DatabaseSection section = this.provider.createSection("warm");
        section.insert(entry("a", "k", "v"));

        // Remove the backing file behind the section's back: a memory-served FULL section
        // must not notice until it is told to reload.
        assertTrue(this.entryFile("warm", "a").toFile().delete());
        assertTrue(section.exists("a"), "FULL reads are served from memory, not the store");

        section.reload();
        assertFalse(section.exists("a"), "reload must re-read the store");

    }

    // ------------------------------------------------------------------ LAZY

    @Test
    void lazySectionLoadsOnFirstAccessNotAtCreation() {

        final DatabaseSection section = this.provider.createSection("deferred", SectionConfig.lazy());

        // Appears after creation but before any data access: a section that loaded at
        // creation time (FULL) would miss it, a lazily loaded one must see it.
        this.writeExternalEntry("deferred", "early");

        assertEquals(1, section.count(), "the first access loads what is in the store at that moment");
        assertTrue(section.exists("early"));

        // Once warm it behaves like FULL: external changes are invisible until reload.
        this.writeExternalEntry("deferred", "late");
        assertFalse(section.exists("late"));

        section.reload();
        assertTrue(section.exists("late"), "LAZY reload defers the re-read to the next access");

    }

    // --------------------------------------------------------------- BOUNDED

    @Test
    void boundedSectionServesHitsFromCacheAndMissesFromStore() {

        final DatabaseSection section = this.provider.createSection("bounded", SectionConfig.bounded(2));

        for (int i = 0; i < 5; i++) section.insert(entry("e" + i, "n", Integer.toString(i)));

        // Aggregates always come from the store - a partial cache cannot answer them.
        assertEquals(5, section.count());
        assertEquals(5, section.getEntries().size());

        // Read-through: every entry is reachable even though at most 2 are ever in memory.
        for (int i = 0; i < 5; i++) {
            assertEquals(Integer.toString(i), section.findEntryById("e" + i).orElseThrow().getMetaData().getString("n"));
        }

        // A just-read entry is cached: removing its file must not make it disappear ...
        assertTrue(section.findEntryById("e4").isPresent());
        assertTrue(this.entryFile("bounded", "e4").toFile().delete());
        assertTrue(section.findEntryById("e4").isPresent(), "cached hit must not touch the store");

        // ... but existence checks go remote and see the truth immediately.
        assertFalse(section.exists("e4"));

        // And a genuinely uncached miss is re-checked against the store every time.
        assertTrue(section.findEntryById("nope").isEmpty());
        this.writeExternalEntry("bounded", "nope");
        assertTrue(section.findEntryById("nope").isPresent(), "absence is never cached");

    }

    @Test
    void boundedTtlExpiresIntoAFreshStoreRead() throws InterruptedException {

        final DatabaseSection section = this.provider.createSection("ttl", SectionConfig.bounded(10, Duration.ofMillis(150)));

        section.insert(entry("k", "v", "old"));
        assertEquals("old", section.findEntryById("k").orElseThrow().getMetaData().getString("v"));

        // Change the store behind the cache's back: within the TTL the stale value is served.
        new JsonDocument().append("id", "k").append("data", new JsonDocument("v", "new")).write(this.entryFile("ttl", "k"));
        assertEquals("old", section.findEntryById("k").orElseThrow().getMetaData().getString("v"),
                "inside the TTL the cached value is authoritative");

        Thread.sleep(300);
        assertEquals("new", section.findEntryById("k").orElseThrow().getMetaData().getString("v"),
                "past the TTL the entry must be re-read from the store");

    }

    @Test
    void boundedWritesGoThroughToTheStore() {

        final DatabaseSection section = this.provider.createSection("wt", SectionConfig.bounded(2));

        section.insert(entry("a", "k", "v1"));
        assertTrue(Files.exists(this.entryFile("wt", "a")), "insert must persist immediately");

        section.update(entry("a", "k2", "v2"));
        assertEquals("v2", section.findEntryById("a").orElseThrow().getMetaData().getString("k2"),
                "the process must read its own write");

        assertThrows(DataAlreadyExist.class, () -> section.insert(entry("a", "x", "y")));

        section.delete("a");
        assertTrue(Files.notExists(this.entryFile("wt", "a")));
        assertThrows(NoSuchEntryFound.class, () -> section.delete("a"));

    }

    // ------------------------------------------------------------------ NONE

    @Test
    void noneSectionPushesEveryOperationToTheStore() {

        final DatabaseSection section = this.provider.createSection("logs", SectionConfig.none());

        section.insert(entry("l1", "msg", "boot"));
        assertTrue(section.exists("l1"));
        assertEquals(1, section.count());
        assertEquals("boot", section.findEntryById("l1").orElseThrow().getMetaData().getString("msg"));

        // External changes are visible immediately - there is no cache to go stale.
        this.writeExternalEntry("logs", "l2");
        assertTrue(section.exists("l2"), "NONE reads the store on every call");
        assertEquals(2, section.count());
        assertEquals(2, section.getEntries().size());

        assertThrows(DataAlreadyExist.class, () -> section.insert(entry("l2", "x", "y")));

        section.delete("l2");
        assertFalse(section.exists("l2"));

        section.reload(); // must be a harmless no-op
        assertEquals(1, section.count());

        section.clear();
        assertEquals(0, section.count());

    }

    // -------------------------------------------------- reconfiguration rules

    @Test
    void reconfiguringASectionReplacesTheInstanceOnlyOnRealChange() {

        final DatabaseSection full = this.provider.createSection("cfg");
        assertSame(full, this.provider.createSection("cfg"), "same implicit config keeps the instance");
        assertSame(full, this.provider.createSection("cfg", SectionConfig.full()), "same explicit config keeps the instance");

        final DatabaseSection bounded = this.provider.createSection("cfg", SectionConfig.bounded(3));
        assertNotSame(full, bounded, "a different config must replace the instance");

        assertSame(bounded, this.provider.createSection("cfg", SectionConfig.bounded(3)), "re-declaring identically keeps the instance");
        assertSame(bounded, this.provider.createSection("cfg"),
                "the plain overload must respect the registered config instead of silently reverting to FULL");
        assertSame(bounded, this.provider.getSection("cfg").orElseThrow());

    }

}
