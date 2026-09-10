package de.lino.database.database;

import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TOML file store, end to end through {@link DatabaseType#TOML}: the same section
 * semantics as every other backend (it runs on the shared engine), plus the properties that
 * are this store's own reason to exist - human-readable TOML on disk, faithful number types,
 * and clean replace-on-update semantics.
 */
class TomlStoreTest {

    @TempDir
    Path root;

    DatabaseProvider provider;

    @BeforeEach
    void setUp() {
        final DatabaseRepositoryRegistry registry = new DatabaseRepositoryRegistry(false);
        this.provider = registry.registerDatabaseProvider(1, DatabaseType.TOML,
                new Credentials(this.root.resolve("config.json"), this.root.resolve("repo")));
    }

    private Path entryFile(String section, String id) {
        return this.root.resolve("repo").resolve(section).resolve(id + ".toml");
    }

    @Test
    void storesHumanReadableTomlWithFaithfulTypes() throws IOException {

        final DatabaseSection section = this.provider.createSection("players");

        section.insert(new DatabaseEntry("Lino", new JsonDocument("data", new JsonDocument("name", "lino")
                .append("age", 23)
                .append("height", 1.86)
                .append("active", true)
                .append("pet", new JsonDocument("kind", "Golden Retriever")))));

        final String toml = Files.readString(this.entryFile("players", "Lino"));

        assertTrue(toml.contains("id = \"Lino\""), "top-level id key, got:\n" + toml);
        assertTrue(toml.contains("[data]"), "data table, got:\n" + toml);
        assertTrue(toml.contains("age = 23") && !toml.contains("age = 23.0"),
                "integral numbers must stay integral, got:\n" + toml);
        assertTrue(toml.contains("height = 1.86"), "fractional numbers stay floats, got:\n" + toml);
        assertTrue(toml.contains("active = true"));
        assertTrue(toml.contains("[data.pet]"), "nested objects become nested tables, got:\n" + toml);

        // And it reads back as the same entry shape every other store produces.
        final DatabaseEntry loaded = this.provider.createSection("players").findEntryById("Lino").orElseThrow();
        assertEquals("lino", loaded.getMetaData().getString("name"));
        assertEquals(23, loaded.getMetaData().getInteger("age"));
        assertEquals(1.86, loaded.getMetaData().getDouble("height"));
        assertTrue(loaded.getMetaData().getBoolean("active"));
        assertEquals("Golden Retriever", loaded.getMetaData().getMetaData("pet").getString("kind"));

    }

    @Test
    void updateReplacesInsteadOfMerging() {

        final DatabaseSection section = this.provider.createSection("replace");

        section.insert(new DatabaseEntry("k", new JsonDocument("data", new JsonDocument("old", "yes").append("keep", "no"))));
        section.update(new DatabaseEntry("k", new JsonDocument("data", new JsonDocument("fresh", "yes"))));

        section.reload();
        final DatabaseEntry loaded = section.findEntryById("k").orElseThrow();

        assertTrue(loaded.getMetaData().contains("fresh"));
        assertFalse(loaded.getMetaData().contains("old"), "TOML updates replace the stored document outright");

    }

    @Test
    void behavesLikeEveryOtherEngineBackedSectionInAllModes() {

        for (final SectionConfig config : List.of(SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(2), SectionConfig.none())) {

            final DatabaseSection section = this.provider.createSection("mode-" + config.cacheMode(), config);

            section.insert(new DatabaseEntry("a", new JsonDocument("data", new JsonDocument("v", "1"))));
            section.insert(new DatabaseEntry("b", new JsonDocument("data", new JsonDocument("v", "2"))));

            assertEquals(2, section.count());
            assertTrue(section.exists("a"));
            assertEquals("1", section.findEntryById("a").orElseThrow().getMetaData().getString("v"));
            assertThrows(DataAlreadyExist.class, () -> section.insert(new DatabaseEntry("a", new JsonDocument("data", new JsonDocument()))));
            assertThrows(NoSuchEntryFound.class, () -> section.delete("ghost"));

            section.delete("b");
            assertEquals(1, section.count());
            assertEquals(List.of("a"), section.getEntries(0, 10).stream().map(DatabaseEntry::getId).toList());

        }

    }

    @Test
    void foreignAndCorruptFilesFollowTheStoreCorruptionContract() throws IOException {

        final DatabaseSection section = this.provider.createSection("dirty");
        section.insert(new DatabaseEntry("good", new JsonDocument("data", new JsonDocument("v", "1"))));

        // A non-.toml file is no entry and must simply be ignored.
        Files.writeString(this.root.resolve("repo").resolve("dirty").resolve(".DS_Store"), "junk");
        section.reload();
        assertEquals(1, section.count());

        // A .toml file that is not a valid entry surfaces as NoSuchDataFound on load,
        // exactly like the JSON store's contract.
        Files.writeString(this.root.resolve("repo").resolve("dirty").resolve("broken.toml"), "not [valid toml");
        assertThrows(NoSuchDataFound.class, section::reload);

    }

    @Test
    void discoveredLazilyLikeEveryOtherProvider() {

        final DatabaseSection section = this.provider.createSection("kept");
        section.insert(new DatabaseEntry("x", new JsonDocument("data", new JsonDocument("v", "42"))));

        this.provider.reload();

        assertTrue(this.provider.existsSection("kept"));
        assertEquals("42", this.provider.getSection("kept").orElseThrow()
                .findEntryById("x").orElseThrow().getMetaData().getString("v"));

    }

}
