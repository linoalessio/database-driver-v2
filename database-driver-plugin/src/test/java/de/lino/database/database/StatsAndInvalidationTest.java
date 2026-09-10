package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.nosql.json.JsonDatabaseProvider;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's per-section counters ({@link AbstractCachedDatabaseSection#stats()}) and the
 * external-invalidation hook ({@link AbstractCachedDatabaseSection#onExternalInvalidate}),
 * against the JSON store where every store-side effect is directly observable.
 */
class StatsAndInvalidationTest {

    @TempDir
    Path root;

    JsonDatabaseProvider provider;

    @BeforeEach
    void setUp() {
        new DatabaseRepositoryRegistry(false);
        this.provider = new JsonDatabaseProvider(new Credentials(this.root.resolve("config.json"), this.root.resolve("repo")));
    }

    private static DatabaseEntry entry(String id, String value) {
        return new DatabaseEntry(id, new JsonDocument("data", new JsonDocument("v", value)));
    }

    private void writeExternal(String section, String id, String value) {
        new JsonDocument().append("id", id).append("data", new JsonDocument("v", value))
                .write(this.root.resolve("repo").resolve(section).resolve(id + ".json"));
    }

    @Test
    void boundedStatsSeparateHitsFromMisses() {

        final AbstractCachedDatabaseSection section =
                (AbstractCachedDatabaseSection) this.provider.createSection("stats", SectionConfig.bounded(10));

        // A write-through insert pre-caches, so the three reads that follow are pure hits.
        section.insert(entry("hot", "1"));
        for (int i = 0; i < 3; i++) section.findEntryById("hot");

        AbstractCachedDatabaseSection.SectionStats stats = section.stats();
        assertEquals(3, stats.cacheHits());
        assertEquals(0, stats.cacheMisses());
        assertEquals(1.0, stats.cacheHitRatio());

        // An entry this process never wrote: the first read must load (miss), the second hits.
        this.writeExternal("stats", "cold", "2");
        section.findEntryById("cold");
        section.findEntryById("cold");

        stats = section.stats();
        assertEquals(4, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());

        // A lookup of a missing id reads the store (miss) and is never negatively cached -
        // the repeat is a second miss.
        section.findEntryById("ghost");
        section.findEntryById("ghost");

        stats = section.stats();
        assertEquals(3, stats.cacheMisses());
        assertTrue(stats.cacheHitRatio() < 1.0);

    }

    @Test
    void fullLoadCountersTrackWarmupAndReload() {

        final AbstractCachedDatabaseSection section =
                (AbstractCachedDatabaseSection) this.provider.createSection("loads");

        AbstractCachedDatabaseSection.SectionStats stats = section.stats();
        assertEquals(1, stats.fullLoads(), "createSection warms a FULL section: exactly one load");

        section.reload();
        stats = section.stats();
        assertEquals(2, stats.fullLoads());
        assertTrue(stats.fullLoadNanos() > 0, "load duration must be measured");

        assertEquals(0, stats.cacheMisses(), "a warm FULL section answers point reads from memory");

    }

    @Test
    void externalInvalidateEvictsFromABoundedCache() {

        final AbstractCachedDatabaseSection section =
                (AbstractCachedDatabaseSection) this.provider.createSection("inval", SectionConfig.bounded(10));

        section.insert(entry("k", "old"));
        assertEquals("old", section.findEntryById("k").orElseThrow().getMetaData().getString("v"));

        // Another process changes the entry: the cache still serves the stale value ...
        this.writeExternal("inval", "k", "new");
        assertEquals("old", section.findEntryById("k").orElseThrow().getMetaData().getString("v"));

        // ... until the change notification arrives, after which the next read re-fetches.
        section.onExternalInvalidate("k");
        assertEquals("new", section.findEntryById("k").orElseThrow().getMetaData().getString("v"));

    }

    @Test
    void externalInvalidateDropsFromAMaterializedViewUntilReload() {

        final AbstractCachedDatabaseSection section =
                (AbstractCachedDatabaseSection) this.provider.createSection("invalFull");

        section.insert(entry("k", "v"));
        section.onExternalInvalidate("k");

        // Documented map-mode semantics: dropped means absent until the next full load.
        assertFalse(section.exists("k"));
        section.reload();
        assertTrue(section.exists("k"));

    }

}
