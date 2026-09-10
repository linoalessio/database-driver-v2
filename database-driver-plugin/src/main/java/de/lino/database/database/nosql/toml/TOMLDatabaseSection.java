package de.lino.database.database.nosql.toml;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.CacheMode;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * The {@link DatabaseSection} backing one directory of TOML files, one file per entry, named
 * {@code <id>.toml} - the JSON file store's layout with human-editable TOML as the on-disk
 * syntax, for data that doubles as configuration. All caching lives in
 * {@link AbstractCachedDatabaseSection}; this class only supplies the directory's storage
 * primitives, with every JSON-to-TOML modelling decision delegated to
 * {@link TomlDocumentMapper} (read its rules before storing unusual documents - TOML cannot
 * hold {@code null}s or mixed-type arrays).
 * <p>
 * Each file carries the entry's full envelope - a top-level {@code id} key plus a
 * {@code [data]} table - e.g.:
 * <pre>{@code
 * id = "Lino"
 *
 * [data]
 * name = "lino"
 * age = 23
 * }</pre>
 * Unlike the JSON store, whose {@code persistUpdate} historically merges into the previously
 * stored document, an update here simply <em>replaces</em> the entry's file with the given
 * entry's state - the semantics {@link DatabaseSection#update} documents, with no legacy
 * behavior to preserve in a new backend.
 */
@Getter
public class TOMLDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The file extension every entry's file carries.
     */
    private static final String EXTENSION = ".toml";

    /**
     * The login credentials this section was constructed with, providing the file repository
     * root {@link #parent} resolves against.
     */
    private final Credentials credentials;

    /**
     * The directory every entry's TOML file is stored in.
     */
    private final Path parent;

    /**
     * Creates (if not already present) {@link #parent} and loads its existing entries into
     * memory immediately - the constructor form every other backend offers for direct
     * instantiation outside a provider.
     *
     * @param name        this section's directory name
     * @param credentials the login credentials, providing the file repository root this
     *                    section's directory lives under
     */
    public TOMLDatabaseSection(@NotNull String name, @NotNull Credentials credentials) {
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
    public TOMLDatabaseSection(@NotNull String name, @NotNull Credentials credentials, @NotNull SectionConfig config) {

        super(name, config);
        this.credentials = credentials;
        this.parent = Paths.get(credentials.getFileRepository(), name);

        FileProvider.getInstance().createDirectory(this.parent);

    }

    /**
     * {@inheritDoc}
     * <p>
     * Iterates every {@value #EXTENSION} file currently in {@link #parent}, (re-)creating the
     * directory first so a freshly created section starts from an existing, empty directory
     * rather than failing to list a missing one. Files with any other extension (editor
     * backups, {@code .DS_Store} ...) are ignored rather than parsed as entries.
     */
    @Override
    protected void loadAll(@NotNull Consumer<DatabaseEntry> consumer) {

        FileProvider.getInstance().createDirectory(this.parent);

        Arrays.stream(Objects.requireNonNull(this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(EXTENSION)))).forEach(path -> {

            final String id = path.getName().replace(EXTENSION, "");
            consumer.accept(this.readEntry(id, path.toPath()));

        });

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single file lookup: the entry exists exactly if its {@code <id>.toml} file does.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull String id) {

        final Path path = this.entryFile(id);
        if (Files.notExists(path)) return Optional.empty();

        return Optional.of(this.readEntry(id, path));

    }

    /**
     * Parses one entry's TOML file into the same {@code {id, data}}-enveloped
     * {@link DatabaseEntry} shape the JSON store produces, so a consumer switching stores sees
     * identical entries.
     *
     * @param id   the entry's id, already derived from the file name by the caller
     * @param path the entry's TOML file
     * @return the parsed entry
     * @throws NoSuchDataFound if the file is not parseable TOML or holds no {@code [data]}
     *                         table - either way not an entry of this store
     */
    private @NotNull DatabaseEntry readEntry(@NotNull String id, @NotNull Path path) {

        JsonDocument document;

        try {
            document = TomlDocumentMapper.fromToml(Files.readString(path, StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException unreadable) {
            // Same corruption contract as the JSON store: unreadable and shape-less files
            // surface as NoSuchDataFound below, not as backend-specific parse errors.
            document = new JsonDocument();
        }

        if (!document.contains("data")) throw new NoSuchDataFound(id);

        return new DatabaseEntry(id, document);

    }

    @Override
    protected void persistInsert(@NotNull DatabaseEntry databaseEntry) {
        this.writeEntry(databaseEntry);
    }

    /**
     * {@inheritDoc}
     * <p>
     * A plain replace of the entry's file - see the class documentation for why this backend
     * has no merge semantics.
     */
    @Override
    protected void persistUpdate(@NotNull DatabaseEntry databaseEntry) {
        this.writeEntry(databaseEntry);
    }

    @Override
    protected void persistDelete(@NotNull String id) {
        FileProvider.getInstance().deleteFile(this.entryFile(id));
    }

    @Override
    protected long countRemote() {
        final File[] files = this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(EXTENSION));
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

        final File[] files = this.parent.toFile().listFiles((dir, fileName) -> fileName.endsWith(EXTENSION));
        if (files == null) return List.of();

        final List<String> ids = new ArrayList<>(files.length);
        for (final File file : files) ids.add(file.getName().replace(EXTENSION, ""));
        ids.sort(null);

        if (offset >= ids.size()) return List.of();

        final List<DatabaseEntry> page = new ArrayList<>(limit);
        for (final String id : ids.subList((int) offset, (int) Math.min(ids.size(), offset + limit))) {
            page.add(this.readEntry(id, this.entryFile(id)));
        }

        return List.copyOf(page);

    }

    /**
     * Writes {@code databaseEntry}'s file in the class-documented shape (top-level {@code id},
     * {@code [data]} table) - the single write path {@link #persistInsert} and
     * {@link #persistUpdate} share.
     *
     * @param databaseEntry the entry to write
     */
    private void writeEntry(@NotNull DatabaseEntry databaseEntry) {

        // databaseEntry.getDocument() is already the full "data"-enveloped document (see its
        // own javadoc); appending it here as-is under another "data" key would double-wrap it,
        // so its already-unwrapped getMetaData() is used instead, matching the other stores.
        final JsonDocument document = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData());

        try {
            Files.writeString(this.entryFile(databaseEntry.getId()), TomlDocumentMapper.toToml(document), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * Resolves the file an entry with {@code id} is stored in - the single naming rule
     * ({@code <parent>/<id>.toml}) every primitive above shares.
     *
     * @param id the entry's id
     * @return the entry's file path
     */
    private @NotNull Path entryFile(@NotNull String id) {
        return Paths.get(this.parent.toString(), id + EXTENSION);
    }

}
