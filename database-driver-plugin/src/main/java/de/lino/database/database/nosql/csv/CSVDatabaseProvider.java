package de.lino.database.database.nosql.csv;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The CSV-file-based {@link DatabaseProvider}: every {@link DatabaseSection} is one
 * {@code "<name>.csv"} file directly under {@link Credentials}'s {@code getFileRepository()},
 * via {@link CSVDatabaseSection}. Section lifecycle and caching live in
 * {@link AbstractLazyDatabaseProvider}; this class only supplies the file-level storage
 * operations - listing {@value #EXTENSION} files, constructing a {@link CSVDatabaseSection},
 * deleting a file.
 */
public class CSVDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * The file extension every section's file carries.
     */
    private static final String EXTENSION = ".csv";

    /**
     * The directory every section's CSV file lives directly under.
     */
    private final Path repository;

    /**
     * Discovers every existing {@value #EXTENSION} file directly under {@code credentials}'
     * file repository as a section name. Only names - no section objects, no file contents -
     * so construction cost is one directory listing, independent of how much data the
     * repository holds.
     *
     * @param credentials the login credentials, providing the file repository root this
     *                    database's sections live under
     */
    public CSVDatabaseProvider(@NotNull final Credentials credentials) {

        this.repository = Path.of(credentials.getFileRepository());

        this.reload();

    }

    @Override
    public void shutdown() {
    }

    /**
     * {@inheritDoc}
     * <p>
     * Lists the repository's {@value #EXTENSION} files, (re-)creating the repository root
     * first so a fresh installation starts from an existing, empty directory rather than
     * failing to list a missing one; each file name minus the extension is one section name.
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        FileProvider.getInstance().createDirectory(this.repository);

        final File[] files = this.repository.toFile().listFiles((directory, fileName) -> fileName.endsWith(EXTENSION));

        for (final File file : Objects.requireNonNull(files)) {
            consumer.accept(file.getName().substring(0, file.getName().length() - EXTENSION.length()));
        }

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new CSVDatabaseSection(name, this.repository.resolve(name + EXTENSION), config);
    }

    @Override
    protected void dropSectionRemote(@NotNull String name) {
        FileProvider.getInstance().deleteFile(this.repository.resolve(name + EXTENSION));
    }

}
