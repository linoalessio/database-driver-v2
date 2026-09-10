package de.lino.database.database.nosql.csv;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.file.FileProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one CSV file, one row per entry as
 * {@code <base64 id>,<base64 data>} - both columns Base64-encoded so neither an entry's id nor
 * its serialized document can ever contain a comma, quote or newline that would otherwise need
 * RFC 4180-style escaping to round-trip correctly. All caching lives in
 * {@link AbstractCachedDatabaseSection}; this class only supplies the file's storage primitives.
 * <p>
 * A single CSV file has no notion of an in-place row update, so {@link #persistUpdate} and
 * {@link #persistDelete} rewrite the whole file rather than editing a single line, while
 * {@link #persistInsert} just appends; for the same reason every point primitive
 * ({@link #fetchOne}, {@link #existsRemote}, {@link #countRemote}) is a scan over the file's
 * lines rather than a true point lookup - a full in-memory cache remains the natural fit for
 * this store.
 */
@Getter
public class CSVDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The CSV file this section wraps.
     */
    private final Path file;

    /**
     * Creates (if not already present) {@code file} and loads its existing rows into the
     * inherited in-memory view, via {@link #reload()}.
     *
     * @param name this section's file name, without the {@code .csv} extension
     * @param file the CSV file this section wraps
     */
    public CSVDatabaseSection(@NotNull final String name, @NotNull final Path file) {

        super(name);
        this.file = file;

        this.reload();

    }

    /**
     * {@inheritDoc}
     * <p>
     * Streams {@link #file} line by line rather than reading every line into memory first,
     * (re-)creating the file beforehand so a freshly created section starts from an existing,
     * empty file rather than failing to read a missing one. Blank lines are skipped, matching
     * what {@link #persistInsert}'s trailing line separator leaves behind.
     */
    @Override
    protected void loadAll(@NotNull final Consumer<DatabaseEntry> consumer) {

        FileProvider.getInstance().createFile(this.file);

        try (final var lines = Files.lines(this.file, StandardCharsets.UTF_8)) {

            lines.forEach(line -> {
                if (line.isBlank()) return;
                consumer.accept(parseRow(line));
            });

        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A bounded scan over the file's rows, comparing decoded ids - a single CSV file offers no
     * cheaper point lookup; see the class documentation.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull final String id) {

        for (final String line : readLines(this.file)) {
            if (line.isBlank()) continue;
            if (rowId(line).equals(id)) return Optional.of(parseRow(line));
        }

        return Optional.empty();

    }

    @Override
    protected void persistInsert(@NotNull final DatabaseEntry databaseEntry) {

        try {
            Files.writeString(this.file, row(databaseEntry) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    protected void persistUpdate(@NotNull final DatabaseEntry databaseEntry) {
        this.rewrite(databaseEntry.getId(), databaseEntry);
    }

    @Override
    protected void persistDelete(@NotNull final String id) {
        this.rewrite(id, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * A scan counting the file's non-blank lines - each one is exactly one row.
     */
    @Override
    protected long countRemote() {
        return readLines(this.file).stream().filter(line -> !line.isBlank()).count();
    }

    /**
     * {@inheritDoc}
     * <p>
     * A bounded scan over the file's rows, comparing decoded ids only - the row's data column
     * is never parsed here.
     */
    @Override
    protected boolean existsRemote(@NotNull final String id) {

        for (final String line : readLines(this.file)) {
            if (!line.isBlank() && rowId(line).equals(id)) return true;
        }

        return false;

    }

    @Override
    protected void clearRemote() {

        try {
            Files.writeString(this.file, "", StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * Rewrites {@link #file} without the row stored under {@code rowId}, appending
     * {@code replacement}'s row instead if one is given - the single read-modify-write shape
     * {@link #persistUpdate} (replace) and {@link #persistDelete} (drop) share, since a CSV
     * file cannot edit one line in place.
     *
     * @param rowId       the id whose stored row is removed
     * @param replacement the entry whose row is appended in its place, or {@code null} to just
     *                    drop the row
     */
    private void rewrite(@NotNull final String rowId, @Nullable final DatabaseEntry replacement) {

        final StringBuilder builder = new StringBuilder();

        for (final String line : readLines(this.file)) {
            if (line.isBlank() || rowId(line).equals(rowId)) continue;
            builder.append(line).append(System.lineSeparator());
        }

        if (replacement != null) builder.append(row(replacement)).append(System.lineSeparator());

        try {
            Files.writeString(this.file, builder.toString(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * Decodes a row's id column without touching its data column, so id-only scans
     * ({@link #existsRemote}, {@link #rewrite}) skip the far larger document payload.
     *
     * @param line the row to read
     * @return the row's decoded id
     */
    private static @NotNull String rowId(@NotNull final String line) {
        return decode(line.substring(0, line.indexOf(',')));
    }

    /**
     * Parses one CSV row back into a {@link DatabaseEntry}, the inverse of {@link #row}.
     *
     * @param line the row to parse
     * @return the parsed entry
     */
    private static @NotNull DatabaseEntry parseRow(@NotNull final String line) {

        final int separator = line.indexOf(',');
        final String id = decode(line.substring(0, separator));
        final byte[] data = Base64.getDecoder().decode(line.substring(separator + 1));

        return new DatabaseEntry(id, new JsonDocument(data));

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
