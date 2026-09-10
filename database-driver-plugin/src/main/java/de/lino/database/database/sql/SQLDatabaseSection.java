package de.lino.database.database.sql;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one SQL table, shared by every SQL vendor this driver
 * supports. All caching lives in {@link AbstractCachedDatabaseSection}; this class only supplies
 * the table's storage primitives - each one a single parameterized statement against the
 * {@code (id, data)} schema the constructor creates.
 */
@Getter
public class SQLDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The connection pool shared with this section's owning {@link SQLDatabaseProvider} and
     * every one of its sibling sections.
     */
    private final SQLExecution sqlExecution;

    /**
     * Creates (if not already present) this section's table and loads its existing rows into
     * the inherited in-memory view, via {@link #reload()}.
     *
     * @param databaseType the SQL vendor {@code sqlExecution} is connected to, used to pick this
     *                     vendor's BLOB column type
     * @param name         this section's table name
     * @param sqlExecution the connection pool to run every query and update through
     */
    @SneakyThrows
    public SQLDatabaseSection(@NotNull DatabaseType databaseType, @NotNull String name, @NotNull SQLExecution sqlExecution) {

        super(name);
        this.sqlExecution = sqlExecution;

        String sqlStatement = "";
        switch (databaseType) {

            case POSTGRES_SQL -> sqlStatement = "BYTEA";
            case MY_SQL, MARIA_DB ->  sqlStatement = "LONGBLOB";
            case SQLITE, H2_DB, ORACLE, APACHE_DERBY ->  sqlStatement = "BLOB";
            case MICROSOFT_SQL_SERVER ->  sqlStatement = "VARBINARY(MAX)";

        }

        this.sqlExecution.executeUpdateAsync("CREATE TABLE IF NOT EXISTS " + name + " (id TEXT, data " + sqlStatement + ");").get();
        this.reload();

    }

    /**
     * How many rows {@link #loadAll} fetches from the server per round trip. Bounds a full
     * load's transient memory to roughly this many rows' raw bytes on top of whatever the
     * engine builds from them - without it, the JDBC driver buffers the complete table in
     * memory before the first row is even parsed, which for a multi-gigabyte table is a
     * boot-time OutOfMemoryError (and the half-read connection it leaves behind desyncs with a
     * "Unexpected packet type" error on its next use).
     */
    private static final int RELOAD_FETCH_SIZE = 256;

    /**
     * {@inheritDoc}
     * <p>
     * Streams {@code SELECT * FROM <table>} in {@link #RELOAD_FETCH_SIZE}-row batches, never
     * buffering the result set whole - see {@link SQLExecution#executeStreamingQuery} for why
     * the plain query variant would OOM on a large table.
     */
    @Override
    protected void loadAll(@NotNull Consumer<DatabaseEntry> consumer) {

        this.sqlExecution.executeStreamingQuery("SELECT * FROM " + this.getName(), RELOAD_FETCH_SIZE, resultSet -> {

            try {

                while (resultSet.next()) {

                    final String id = resultSet.getString("id");
                    final DatabaseEntry databaseEntry = this.readEntry(id, resultSet);
                    if (databaseEntry != null) consumer.accept(databaseEntry);

                }

            } catch (final SQLException exception) {
                exception.printStackTrace();
            }

            return true;
        }, true);

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single indexed-lookup-shaped {@code SELECT ... WHERE id = ?}. A row whose {@code data}
     * column is unexpectedly {@code NULL} surfaces as absent rather than throwing, because
     * {@link SQLExecution#executeQuery}'s error handling maps any failure inside the row mapper
     * to the default value.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull String id) {

        return this.sqlExecution.<Optional<DatabaseEntry>>executeQuery("SELECT data FROM " + this.getName() + " WHERE id = ?", resultSet -> {

            try {
                if (!resultSet.next()) return Optional.empty();
                return Optional.ofNullable(this.readEntry(id, resultSet));
            } catch (final SQLException exception) {
                exception.printStackTrace();
                return Optional.empty();
            }

        }, Optional.empty(), id);

    }

    /**
     * Parses the current row of {@code resultSet} into a {@link DatabaseEntry}, the one row
     * shape ({@code data} BLOB holding the serialized {@link JsonDocument}) every query here
     * shares.
     *
     * @param id        the row's primary key, already read by the caller
     * @param resultSet positioned on the row to parse
     * @return the parsed entry, or {@code null} if the raw bytes could not be read back
     * @throws SQLException     if reading the {@code data} column fails
     * @throws NoSuchDataFound  if the row exists but its {@code data} column is {@code NULL}
     */
    private DatabaseEntry readEntry(String id, @NotNull ResultSet resultSet) throws SQLException {

        final byte[] data = resultSet.getBytes("data");
        if (data == null) throw new NoSuchDataFound(id);

        try (final InputStream inputStream = new ByteArrayInputStream(data)) {
            return new DatabaseEntry(id, new JsonDocument(inputStream));
        } catch (final IOException exception) {
            exception.printStackTrace();
            return null;
        }

    }

    @Override
    protected void persistInsert(@NotNull DatabaseEntry databaseEntry) {
        this.sqlExecution.executeUpdate("INSERT INTO " + this.getName() + " (id, data) VALUES (?, ?);", databaseEntry.getId(), databaseEntry.getDocument().toBytes());
    }

    @Override
    protected void persistUpdate(@NotNull DatabaseEntry databaseEntry) {
        this.sqlExecution.executeUpdate("UPDATE " + this.getName() + " SET data = ? WHERE id = ?", databaseEntry.getDocument().toBytes(), databaseEntry.getId());
    }

    @Override
    protected void persistDelete(@NotNull String id) {
        this.sqlExecution.executeUpdate("DELETE FROM " + this.getName() + " WHERE id = ?", id);
    }

    @Override
    protected long countRemote() {

        return this.sqlExecution.executeQuery("SELECT COUNT(*) FROM " + this.getName(), resultSet -> {

            try {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            } catch (final SQLException exception) {
                exception.printStackTrace();
                return 0L;
            }

        }, 0L);

    }

    @Override
    protected boolean existsRemote(@NotNull String id) {

        return this.sqlExecution.executeQuery("SELECT 1 FROM " + this.getName() + " WHERE id = ?", resultSet -> {

            try {
                return resultSet.next();
            } catch (final SQLException exception) {
                exception.printStackTrace();
                return false;
            }

        }, false, id);

    }

    @Override
    protected void clearRemote() {
        this.sqlExecution.executeUpdate("TRUNCATE TABLE " + this.getName());
    }

}
