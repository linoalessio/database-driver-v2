package de.lino.database.database.nosql.csv;

import com.google.common.collect.Maps;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseSection} backing one CSV file, one row per entry as
 * {@code <base64 id>,<base64 data>} - both columns Base64-encoded so neither an entry's id nor
 * its serialized document can ever contain a comma, quote or newline that would otherwise need
 * RFC 4180-style escaping to round-trip correctly. Entries are cached in memory (loaded once in
 * the constructor and kept in sync on every write) so reads never touch the filesystem, only
 * writes do; since a CSV file has no notion of an in-place row update, {@link #update} and
 * {@link #delete} rewrite the whole file from {@link #entries} rather than editing a single line,
 * while {@link #insert} just appends.
 */
@Getter
public class CSVDatabaseSection implements DatabaseSection {

    /**
     * This section's file name, without the {@code .csv} extension.
     */
    private final String name;

    /**
     * The CSV file this section wraps.
     */
    private final Path file;

    /**
     * Every entry currently in {@link #file}, keyed by id and kept in sync with the filesystem
     * by every write method; the source of truth for every read method.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * Creates (if not already present) {@code file} and loads its existing rows into
     * {@link #entries}.
     *
     * @param name this section's file name, without the {@code .csv} extension
     * @param file the CSV file this section wraps
     */
    public CSVDatabaseSection(@NotNull final String name, @NotNull final Path file) {

        this.name = name;
        this.file = file;
        this.entries = Maps.newConcurrentMap();

        this.reload();

    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #entries} entirely and re-populates it from every row currently
     * in {@link #file}, the same scan the constructor itself runs - so a row added,
     * changed or removed directly on disk since this section was constructed (e.g. a
     * backup restored while the application was already running) is picked up here
     * even though ordinary reads never touch the filesystem.
     */
    @Override
    public void reload() {

        FileProvider.getInstance().createFile(this.file);
        this.entries.clear();

        for (final String line : readLines(this.file)) {

            if (line.isBlank()) continue;

            final int separator = line.indexOf(',');
            final String id = decode(line.substring(0, separator));
            final byte[] data = Base64.getDecoder().decode(line.substring(separator + 1));

            this.entries.put(id, new DatabaseEntry(id, new JsonDocument(data)));

        }

    }

    @Override
    public void insert(@NotNull final DatabaseEntry databaseEntry) {

        if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new DataAlreadyExist(databaseEntry.getId());

        try {
            Files.writeString(this.file, row(databaseEntry) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull final DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        this.entries.put(databaseEntry.getId(), databaseEntry);
        this.rewrite();

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull final String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        this.entries.remove(id);
        this.rewrite();

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {
        this.entries.clear();
        this.rewrite();
    }

    @Override
    public boolean exists(@NotNull final String id) {
        return this.entries.containsKey(id);
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull final String id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public @UnmodifiableView List<DatabaseEntry> getEntries() {
        return List.copyOf(this.entries.values());
    }

    /**
     * Overwrites {@link #file} with one row per current entry of {@link #entries}.
     */
    private void rewrite() {

        final StringBuilder builder = new StringBuilder();
        for (final DatabaseEntry entry : this.entries.values()) builder.append(row(entry)).append(System.lineSeparator());

        try {
            Files.writeString(this.file, builder.toString(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * Builds {@code databaseEntry}'s CSV row: its Base64-encoded id, a comma, and its
     * Base64-encoded serialized document.
     *
     * @param databaseEntry the entry to build a row for
     * @return the built row, without a trailing line terminator
     */
    private static String row(@NotNull final DatabaseEntry databaseEntry) {
        return encode(databaseEntry.getId()) + "," + Base64.getEncoder().encodeToString(databaseEntry.getDocument().toBytes());
    }

    /**
     * Base64-encodes {@code value} as UTF-8, the inverse of {@link #decode}.
     *
     * @param value the text to encode
     * @return the Base64-encoded text
     */
    private static String encode(@NotNull final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a Base64-encoded, UTF-8 string, the inverse of {@link #encode}.
     *
     * @param value the Base64-encoded text to decode
     * @return the decoded text
     */
    private static String decode(@NotNull final String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    /**
     * Reads every line of {@code file}, or an empty list if it cannot be read.
     *
     * @param file the file to read
     * @return {@code file}'s lines, in order
     */
    @NotNull
    private static List<String> readLines(@NotNull final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            exception.printStackTrace();
            return List.of();
        }
    }

}
