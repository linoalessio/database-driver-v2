package de.lino.database.database.nosql.json;

import com.google.common.collect.Maps;
import de.lino.database.database.auth.Credentials;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

/**
 * The file-based {@link DatabaseProvider}: every {@link DatabaseSection} is a subdirectory of
 * {@link Credentials}'s {@code getFileRepository()}, holding one JSON file per entry, via
 * {@link JsonDatabaseSection}.
 */
public class JsonDatabaseProvider implements DatabaseProvider {

    /**
     * The login credentials this database was constructed with, needed to resolve every new
     * {@link JsonDatabaseSection}'s subdirectory under {@link Credentials}'s {@code getFileRepository()}.
     */
    private final Credentials credentials;

    /**
     * Every registered section, keyed by name.
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * Loads every existing subdirectory of {@code credentials}' file repository as a
     * {@link JsonDatabaseSection}, via {@link #reload()}.
     *
     * @param credentials the login credentials, providing the file repository root this
     *                    database's sections live under
     */
    public JsonDatabaseProvider(@NotNull Credentials credentials) {

        this.credentials = credentials;
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
     * {@link JsonDatabaseSection} per subdirectory currently under {@code credentials}'
     * file repository, the same scan the constructor itself runs - so a subdirectory
     * added or removed directly on disk since this database was constructed (e.g. a
     * backup restored while the application was already running) is picked up here,
     * and every rebuilt section starts with a fresh read of its own contents.
     * <p>
     * Only directories are considered; a stray non-directory file sitting directly in
     * the file repository (most commonly a filesystem-managed one such as macOS'
     * {@code .DS_Store}, dropped in by Finder the moment the folder is ever browsed) is
     * skipped rather than treated as an empty section, which would otherwise fail
     * outright - a {@link JsonDatabaseSection} always expects its own name to resolve to
     * a directory it can list.
     */
    @Override
    public void reload() {

        FileProvider.getInstance().createDirectory(Paths.get(credentials.getFileRepository()));
        this.databaseSections.clear();

        Arrays.stream(Objects.requireNonNull(Paths.get(credentials.getFileRepository()).toFile().listFiles(File::isDirectory))).forEach(path -> {

            final String name = path.getName();
            final DatabaseSection databaseSection = new JsonDatabaseSection(name, credentials);
            this.databaseSections.put(name, databaseSection);

        });

    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new JsonDatabaseSection(key, this.credentials));
    }

    @Override
    public void deleteSection(@NotNull String name) {
        FileProvider.getInstance().deleteDirectory(Paths.get(this.credentials.getFileRepository(), name));
        this.databaseSections.remove(name);
    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return List.copyOf(this.databaseSections.values());
    }

    @Override
    public Optional<DatabaseSection> getSection(@NotNull String name) {
        return Optional.ofNullable(this.databaseSections.get(name));
    }

    @Override
    public void clear() {
        for (DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.databaseSections.clear();
    }

}
