package de.lino.database.database.nosql.toml;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The TOML-file-based {@link DatabaseProvider}: every {@link DatabaseSection} is a subdirectory
 * of {@link Credentials}'s {@code getFileRepository()}, holding one TOML file per entry, via
 * {@link TOMLDatabaseSection} - the JSON file store's layout with hand-editable TOML files.
 * Section lifecycle and caching live in {@link AbstractLazyDatabaseProvider}; this class only
 * supplies the directory-level storage operations - listing subdirectories, constructing a
 * {@link TOMLDatabaseSection}, deleting a subdirectory.
 * <p>
 * Discovery is by directory alone, exactly like the JSON store's - the two cannot tell each
 * other's section directories apart - so give each file-based provider its own repository root
 * rather than pointing a JSON and a TOML provider at the same directory.
 */
public class TOMLDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * The login credentials this database was constructed with, needed to resolve every new
     * {@link TOMLDatabaseSection}'s subdirectory under {@link Credentials}'s {@code getFileRepository()}.
     */
    private final Credentials credentials;

    /**
     * Discovers every existing subdirectory of {@code credentials}' file repository as a
     * section name. Only names - no section objects, no file contents - so construction cost
     * is one directory listing, independent of how much data the repository holds.
     *
     * @param credentials the login credentials, providing the file repository root this
     *                    database's sections live under
     */
    public TOMLDatabaseProvider(@NotNull Credentials credentials) {

        this.credentials = credentials;

        this.reload();

    }

    @Override
    public void shutdown() {
    }

    /**
     * {@inheritDoc}
     * <p>
     * Lists the file repository's subdirectories, (re-)creating the repository root first so a
     * fresh installation starts from an existing, empty directory rather than failing to list
     * a missing one. Only directories are considered; a stray non-directory file sitting
     * directly in the repository (most commonly a filesystem-managed one such as macOS'
     * {@code .DS_Store}) is skipped rather than treated as an empty section.
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        FileProvider.getInstance().createDirectory(Paths.get(this.credentials.getFileRepository()));

        Arrays.stream(Objects.requireNonNull(Paths.get(this.credentials.getFileRepository()).toFile().listFiles(File::isDirectory)))
                .forEach(path -> consumer.accept(path.getName()));

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new TOMLDatabaseSection(name, this.credentials, config);
    }

    @Override
    protected void dropSectionRemote(@NotNull String name) {
        FileProvider.getInstance().deleteDirectory(Paths.get(this.credentials.getFileRepository(), name));
    }

}
