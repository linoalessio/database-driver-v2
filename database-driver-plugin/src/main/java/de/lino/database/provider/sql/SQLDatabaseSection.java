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

import com.google.common.collect.Lists;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Getter
public class SQLDatabaseSection implements DatabaseSection {

    private final String name;
    private final SQLExecution sqlExecution;

    private final List<DatabaseEntry> entries;

    @SneakyThrows
    public SQLDatabaseSection(@NotNull DatabaseType databaseType, @NotNull String name, @NotNull SQLExecution sqlExecution) {

        this.name = name;
        this.sqlExecution = sqlExecution;
        this.entries = Lists.newCopyOnWriteArrayList();

        String sqlStatement = "";
        switch (databaseType) {

            case POSTGRE_SQL -> sqlStatement = "BYTEA";
            case MY_SQL, MARIA_DB ->  sqlStatement = "LONGBLOB";
            case SQLITE, H2_DB ->  sqlStatement = "BLOB";

        }

        this.sqlExecution.executeUpdateAsync("CREATE TABLE IF NOT EXISTS " + name + " (id TEXT, data " + sqlStatement + ");").get();
        this.sqlExecution.executeQueryAsync("SELECT * FROM " + this.name, resultSet -> {

            try {

                while (resultSet.next()) {

                    final String id = resultSet.getString("id");
                    final byte[] data = resultSet.getBytes("data");

                    try (final InputStream inputStream = new ByteArrayInputStream(data)) {
                        JsonDocument jsonDocument = new JsonDocument(inputStream);
                        this.entries.add(new DatabaseEntry(id, jsonDocument));
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
    public void insert(@NotNull String id, @NotNull JsonDocument document) {

        if (this.exists(id)) return;
        this.sqlExecution.executeUpdate("INSERT INTO " + this.name + " (id, data) VALUES (?, ?);", id, document.toBytes());
        this.entries.add(new DatabaseEntry(id, document));

    }

    @Override
    public void update(@NotNull String id, @NotNull JsonDocument document) {

        if (!this.exists(id)) return;

        this.sqlExecution.executeUpdate("UPDATE " + this.name + " SET data = ? WHERE id = ?", document.toBytes(), id);
        this.entries.removeIf(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id));
        this.entries.add(new DatabaseEntry(id, document));

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) return;

        this.sqlExecution.executeUpdate("DELETE FROM " + this.name + " WHERE id = ?", id);
        this.entries.removeIf(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id));

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void delete() {
        this.sqlExecution.executeUpdate("TRUNCATE TABLE " + this.name);
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.stream().anyMatch(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id));
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.stream().filter(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id)).findFirst().orElse(null));
    }

}
