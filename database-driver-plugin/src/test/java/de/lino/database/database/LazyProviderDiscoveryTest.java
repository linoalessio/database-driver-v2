package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.database.nosql.json.JsonDatabaseProvider;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lazy section-discovery contract of {@link AbstractLazyDatabaseProvider}, exercised
 * through the JSON file store: connecting to a database records which sections exist, but
 * constructs no section objects and reads no row data until a section is actually asked for -
 * the historical eager loading is what made provider construction cost heap and time
 * proportional to everything the store held.
 */
class LazyProviderDiscoveryTest {

    @TempDir
    Path root;

    Path repo;

    @BeforeEach
    void setUp() {
        new DatabaseRepositoryRegistry(false);
        this.repo = this.root.resolve("repo");
    }

    private Credentials credentials() {
        return new Credentials(this.root.resolve("config-" + System.nanoTime() + ".json"), this.repo);
    }

    private void seedSection(String section, String id) throws IOException {
        Files.createDirectories(this.repo.resolve(section));
        new JsonDocument().append("id", id).append("data", new JsonDocument("k", "v"))
                .write(this.repo.resolve(section).resolve(id + ".json"));
    }

    @Test
    void providerConstructionReadsNoRowData() throws IOException {

        // A corrupt entry (no "data" envelope) historically blew up provider construction
        // itself, because constructing the provider loaded every row of every section. Now
        // construction must succeed - only actually touching the poisoned section's data may
        // surface the corruption.
        Files.createDirectories(this.repo.resolve("poisoned"));
        Files.writeString(this.repo.resolve("poisoned").resolve("bad.json"), "{\"not\": \"an entry\"}");

        final JsonDatabaseProvider provider = assertDoesNotThrow(() -> new JsonDatabaseProvider(this.credentials()),
                "discovery must not read row data");

        assertTrue(provider.existsSection("poisoned"), "the section is still discovered by name");
        assertThrows(NoSuchDataFound.class, () -> provider.getSection("poisoned").orElseThrow().count(),
                "the corruption surfaces only when the section's data is actually loaded");

    }

    @Test
    void discoveredSectionsExistWithoutBeingMaterialized() throws IOException {

        this.seedSection("alpha", "a1");
        this.seedSection("beta", "b1");

        final JsonDatabaseProvider provider = new JsonDatabaseProvider(this.credentials());

        assertTrue(provider.existsSection("alpha"));
        assertTrue(provider.existsSection("beta"));
        assertFalse(provider.existsSection("gamma"));

        assertEquals(2, provider.getSections().size());
        assertEquals("v", provider.getSection("alpha").orElseThrow()
                .findEntryById("a1").orElseThrow().getMetaData().getString("k"));

    }

    @Test
    void reloadRediscoversNamesAndDetachesOldInstances() throws IOException {

        this.seedSection("stable", "s1");
        final JsonDatabaseProvider provider = new JsonDatabaseProvider(this.credentials());

        final DatabaseSection before = provider.getSection("stable").orElseThrow();
        assertTrue(before.exists("s1"));

        // A section appears and an entry changes on disk, then the provider reloads.
        this.seedSection("appeared", "n1");
        this.seedSection("stable", "s2");
        provider.reload();

        assertTrue(provider.existsSection("appeared"), "reload must pick up new sections");

        final DatabaseSection after = provider.getSection("stable").orElseThrow();
        assertNotSame(before, after, "re-fetching after reload must yield a fresh instance");
        assertTrue(after.exists("s2"), "the fresh instance reflects the store");
        assertFalse(before.exists("s2"), "the old instance keeps its own, now-detached state");

    }

    @Test
    void deleteSectionRemovesStoreAndBookkeeping() throws IOException {

        this.seedSection("doomed", "d1");
        final JsonDatabaseProvider provider = new JsonDatabaseProvider(this.credentials());

        provider.deleteSection("doomed");

        assertFalse(provider.existsSection("doomed"));
        assertTrue(provider.getSection("doomed").isEmpty());
        assertTrue(Files.notExists(this.repo.resolve("doomed")), "the backing directory must be gone");

    }

    @Test
    void createSectionIsWarmButGetSectionStaysColdUntilAccessed() throws IOException {

        this.seedSection("observed", "o1");
        final JsonDatabaseProvider provider = new JsonDatabaseProvider(this.credentials());

        // Materialized via getSection: still cold, so a file added afterwards but before the
        // first data access is included in the load ...
        final DatabaseSection cold = provider.getSection("observed").orElseThrow();
        this.seedSection("observed", "o2");
        assertEquals(2, cold.count(), "a getSection-materialized FULL section loads on first access");

        // ... while createSection warms synchronously: a file added right after it returns is
        // invisible until reload, the historical warm-at-creation contract.
        final DatabaseSection warm = provider.createSection("created");
        warm.insert(new DatabaseEntry("c1", new JsonDocument("data", new JsonDocument("k", "v"))));
        this.seedSection("created", "c2");
        assertEquals(1, warm.count(), "a createSection section is already warm when it returns");

    }

}
