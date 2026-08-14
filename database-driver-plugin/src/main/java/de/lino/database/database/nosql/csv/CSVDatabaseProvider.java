package de.lino.database.database.nosql.csv;

import com.google.common.collect.Maps;
import de.lino.database.database.auth.Credentials;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The CSV-file-based {@link DatabaseProvider}: every {@link DatabaseSection} is one
 * {@code "<name>.csv"} file directly under {@link Credentials}'s {@code getFileRepository()},
 * via {@link CSVDatabaseSection}.
 */
public class CSVDatabaseProvider implements DatabaseProvider {

    /**
     * The file extension every section's file carries.
     */
    private static final String EXTENSION = ".csv";

    /**
     * The directory every section's CSV file lives directly under.
     */
    private final Path repository;

    /**
     * Every registered section, keyed by file name (without {@link #EXTENSION}).
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * Loads every existing {@value #EXTENSION} file directly under {@code credentials}' file
     * repository as a {@link CSVDatabaseSection}.
     *
     * @param credentials the login credentials, providing the file repository root this
     *                    database's sections live under
     */
    public CSVDatabaseProvider(@NotNull final Credentials credentials) {

        this.repository = Path.of(credentials.getFileRepository());
        this.databaseSections = Maps.newConcurrentMap();

        this.reload();

    }

    @Override
    public void shutdown() {
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #databaseSections} entirely and rebuilds it with a fresh
     * {@link CSVDatabaseSection} per {@value #EXTENSION} file currently under
     * {@link #repository}, the same scan the constructor itself runs.
     */
    @Override
    public void reload() {

        FileProvider.getInstance().createDirectory(this.repository);
        this.databaseSections.clear();

        final File[] files = this.repository.toFile().listFiles((directory, fileName) -> fileName.endsWith(EXTENSION));

        for (final File file : Objects.requireNonNull(files)) {
            final String name = file.getName().substring(0, file.getName().length() - EXTENSION.length());
            this.databaseSections.put(name, new CSVDatabaseSection(name, file.toPath()));
        }

    }

    @Override
    public DatabaseSection createSection(@NotNull final String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new CSVDatabaseSection(key, this.repository.resolve(key + EXTENSION)));
    }

    @Override
    public void deleteSection(@NotNull final String name) {
        FileProvider.getInstance().deleteFile(this.repository.resolve(name + EXTENSION));
        this.databaseSections.remove(name);
    }

    @Override
    public boolean existsSection(@NotNull final String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return List.copyOf(this.databaseSections.values());
    }

    @Override
    public Optional<DatabaseSection> getSection(@NotNull final String name) {
        return Optional.ofNullable(this.databaseSections.get(name));
    }

    @Override
    public void clear() {
        for (final DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.databaseSections.clear();
    }

}
