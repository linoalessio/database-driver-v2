package de.lino.database.provider.sql;

/*
 * MIT License
 *
 * Copyright (c) lino, 08.09.2025
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import com.google.common.collect.Maps;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.exception.EntryAlreadyInserted;
import de.lino.database.exception.NoSuchDataFound;
import de.lino.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseSection} backing one SQL table: entries are cached in memory (loaded once
 * in the constructor and kept in sync on every write) so reads ({@link #exists}, {@link #count},
 * {@link #getEntries}, {@link #findEntryById}) never touch the database, only writes do.
 */
@Getter
public class SQLDatabaseSection implements DatabaseSection {

    /**
     * This section's table name.
     */
    private final String name;

    /**
     * The connection pool shared with this section's owning {@link SQLDatabaseProvider} and
     * every one of its sibling sections.
     */
    private final SQLExecution sqlExecution;

    /**
     * Every entry currently in this table, keyed by id and kept in sync with the database by
     * every write method; the source of truth for every read method.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * Creates (if not already present) this section's table and loads its existing rows into
     * {@link #entries}.
     *
     * @param databaseType the SQL vendor {@code sqlExecution} is connected to, used to pick this
     *                     vendor's BLOB column type
     * @param name         this section's table name
     * @param sqlExecution the connection pool to run every query and update through
     */
    @SneakyThrows
    public SQLDatabaseSection(@NotNull DatabaseType databaseType, @NotNull String name, @NotNull SQLExecution sqlExecution) {

        this.name = name;
        this.sqlExecution = sqlExecution;
        this.entries = Maps.newConcurrentMap();

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
     * {@inheritDoc}
     * <p>
     * Discards {@link #entries} entirely and re-populates it from every row currently
     * in this section's table, the same query the constructor itself runs.
     */
    @Override
    @SneakyThrows
    public void reload() {

        this.entries.clear();

        this.sqlExecution.executeQueryAsync("SELECT * FROM " + this.name, resultSet -> {

            try {

                while (resultSet.next()) {

                    final String id = resultSet.getString("id");
                    final byte[] data = resultSet.getBytes("data");

                    if (data == null) throw new NoSuchDataFound(id);

                    try (final InputStream inputStream = new ByteArrayInputStream(data)) {
                        JsonDocument jsonDocument = new JsonDocument(inputStream);
                        this.entries.put(id, new DatabaseEntry(id, jsonDocument));
                    } catch (final IOException exception) {
                        exception.printStackTrace();
                    }

                }

            } catch (final SQLException exception) {
                exception.printStackTrace();
            }

            return true;
        }, true).get();

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new EntryAlreadyInserted(databaseEntry.getId());

        this.sqlExecution.executeUpdate("INSERT INTO " + this.name + " (id, data) VALUES (?, ?);", databaseEntry.getId(), databaseEntry.getDocument().toBytes());

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        this.sqlExecution.executeUpdate("UPDATE " + this.name + " SET data = ? WHERE id = ?", databaseEntry.getDocument().toBytes(), databaseEntry.getId());
        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        this.sqlExecution.executeUpdate("DELETE FROM " + this.name + " WHERE id = ?", id);
        this.entries.remove(id);

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {
        this.sqlExecution.executeUpdate("TRUNCATE TABLE " + this.name);
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.containsKey(id);
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public @UnmodifiableView List<DatabaseEntry> getEntries() {
        return List.copyOf(this.entries.values());
    }

}
