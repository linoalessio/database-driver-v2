package de.lino.database.database.nosql.json;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.CacheMode;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one directory of JSON files, one file per entry, named
 * {@code <id>.json}. All caching lives in {@link AbstractCachedDatabaseSection}; this class only
 * supplies the directory's storage primitives - each one a plain file operation.
 */
@Getter
public class JsonDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The login credentials this section was constructed with, providing the file repository
     * root {@link #parent} resolves against.
     */
    private final Credentials credentials;

    /**
     * The directory every entry's JSON file is stored in.
     */
    private final Path parent;

    /**
     * Creates (if not already present) {@link #parent} and loads its existing entries into
     * memory immediately - the historical constructor, kept with its exact
     * loaded-once-constructed semantics for anyone instantiating sections directly rather than
     * through a provider.
     *
     * @param name        this section's directory name
     * @param credentials the login credentials, providing the file repository root this
     *                    section's directory lives under
     */
    public JsonDatabaseSection(@NotNull String name, @NotNull Credentials credentials) {
        this(name, credentials, SectionConfig.full());
        this.warmUp();
    }

    /**
     * Creates (if not already present) {@link #parent}. No entry is read here - whether and
     * when entries are loaded is the engine's decision per {@code config}, with the owning
     * provider triggering the {@link CacheMode#FULL} warm-up right after construction. The
     * directory itself is still created eagerly, so a freshly created section exists on disk
     * (and survives a provider {@code reload()}) even before anything touches its data.
     *
     * @param name        this section's directory name
     * @param credentials the login credentials, providing the file repository root this
     *                    section's directory lives under
     * @param config      how this section holds entries in memory
     */
    public JsonDatabaseSection(@NotNull String name, @NotNull Credentials credentials, @NotNull SectionConfig config) {

        super(name, config);
        this.credentials = credentials;
        this.parent = Paths.get(credentials.getFileRepository(), name);

        FileProvider.getInstance().createDirectory(this.parent);

    }

    /**
     * {@inheritDoc}
     * <p>
     * Iterates every {@code *.json} file currently in {@link #parent}, (re-)creating the
     * directory first so a freshly created section starts from an existing, empty directory
     * rather than failing to list a missing one.
     * <p>
     * Only files ending in {@code .json} are considered; a stray non-entry file sitting
     * directly in {@link #parent} (most commonly a filesystem-managed one such as macOS'
     * {@code .DS_Store}, dropped in by Finder the moment the folder is ever browsed) is
     * skipped rather than parsed as an entry, which would otherwise fail outright.
     */
    @Override
    protected void loadAll(@NotNull Consumer<DatabaseEntry> consumer) {

        FileProvider.getInstance().createDirectory(this.parent);

        Arrays.stream(Objects.requireNonNull(this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(".json")))).forEach(path -> {

            final String id = path.getName().replace(".json", "");
            consumer.accept(this.readEntry(id, path.toPath()));

        });

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single file lookup: the entry exists exactly if its {@code <id>.json} file does.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull String id) {

        final Path path = this.entryFile(id);
        if (Files.notExists(path)) return Optional.empty();

        return Optional.of(this.readEntry(id, path));

    }

    /**
     * Parses one entry's JSON file, the shared row shape ({@code {"id": ..., "data": ...}})
     * every read here expects.
     *
     * @param id   the entry's id, already derived from the file name by the caller
     * @param path the entry's JSON file
     * @return the parsed entry
     * @throws NoSuchDataFound if the file exists but holds no {@code "data"} envelope,
     *                         indicating a corrupted or foreign file
     */
    private @NotNull DatabaseEntry readEntry(@NotNull String id, @NotNull Path path) {

        final JsonDocument document = JsonDocument.load(path);
        if (!document.contains("data")) throw new NoSuchDataFound(id);

        return new DatabaseEntry(id, document);

    }

    @Override
    protected void persistInsert(@NotNull DatabaseEntry databaseEntry) {

        // databaseEntry.getDocument() is already the full "data"-enveloped document (see its
        // own javadoc); appending it here as-is under another "data" key would double-wrap it,
        // so its already-unwrapped getMetaData() is used instead - the same shape persistUpdate()
        // below writes, so a freshly inserted entry round-trips identically to a later-updated one.
        final JsonDocument document = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData());
        document.write(this.entryFile(databaseEntry.getId()));

    }

    /**
     * {@inheritDoc}
     * <p>
     * Two historical write shapes, preserved exactly: an entry whose document already carries
     * its {@code "id"} (the shape {@link #loadAll} produces when reading a file back) simply
     * replaces the stored file outright, while an entry without one (the shape a caller builds
     * fresh) is <em>merged</em> - its metadata keys are added on top of the previously stored
     * entry's metadata, so keys absent from the update survive. The previous entry is taken
     * from the engine's in-memory view when available (see
     * {@link AbstractCachedDatabaseSection#cachedEntry}) because the in-memory copy is what
     * this merge historically read - it is not guaranteed byte-identical to the file - and
     * only read from disk when nothing is cached.
     */
    @Override
    protected void persistUpdate(@NotNull DatabaseEntry databaseEntry) {

        if (databaseEntry.getDocument().contains("id")) {

            this.persistDelete(databaseEntry.getId());
            this.persistInsert(databaseEntry);

            return;
        }

        final DatabaseEntry existing = this.cachedEntry(databaseEntry.getId())
                .or(() -> this.fetchOne(databaseEntry.getId()))
                .orElseThrow(() -> new NoSuchEntryFound(databaseEntry.getId()));

        final JsonDocument data = existing.getMetaData();

        databaseEntry.getMetaData().asMap().forEach((key, value) -> data.getJsonObject().add(key, value));
        existing.getDocument()
                .append("id", databaseEntry.getId())
                .append("data", data)
                .write(this.entryFile(databaseEntry.getId()));

    }

    @Override
    protected void persistDelete(@NotNull String id) {
        FileProvider.getInstance().deleteFile(this.entryFile(id));
    }

    @Override
    protected long countRemote() {
        final File[] files = this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(".json"));
        return files == null ? 0L : files.length;
    }

    @Override
    protected boolean existsRemote(@NotNull String id) {
        return Files.exists(this.entryFile(id));
    }

    @Override
    protected void clearRemote() {
        FileProvider.getInstance().deleteAllFilesInDirectory(this.parent);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Pushed down as far as a directory store allows: the page is chosen on file
     * <em>names</em> alone (one directory listing, sorted by id), and only the files actually
     * inside the page window are opened and parsed - the payload cost of a page is O(limit),
     * not O(section).
     */
    @Override
    protected @UnmodifiableView List<DatabaseEntry> pageRemote(long offset, int limit) {

        final File[] files = this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(".json"));
        if (files == null) return List.of();

        final List<String> ids = new ArrayList<>(files.length);
        for (final File file : files) ids.add(file.getName().replace(".json", ""));
        ids.sort(null);

        if (offset >= ids.size()) return List.of();

        final List<DatabaseEntry> page = new ArrayList<>(limit);
        for (final String id : ids.subList((int) offset, (int) Math.min(ids.size(), offset + limit))) {
            page.add(this.readEntry(id, this.entryFile(id)));
        }

        return List.copyOf(page);

    }

    /**
     * Resolves the file an entry with {@code id} is stored in - the single naming rule
     * ({@code <parent>/<id>.json}) every primitive above shares.
     *
     * @param id the entry's id
     * @return the entry's file path
     */
    private @NotNull Path entryFile(@NotNull String id) {
        return Paths.get(this.parent.toString(), id + ".json");
    }

}
