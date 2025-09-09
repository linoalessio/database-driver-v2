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
import com.google.common.collect.Maps;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class SQLDatabaseProvider implements DatabaseProvider {

    private final DatabaseType databaseType;
    private final SQLExecution sqlExecution;
    private final Map<String, DatabaseSection> databaseSections;

    @SneakyThrows
    public SQLDatabaseProvider(@NotNull DatabaseType databaseType, @NotNull SQLExecution sqlExecution) {

        this.databaseType = databaseType;
        this.sqlExecution = sqlExecution;
        this.databaseSections = Maps.newConcurrentMap();

        this.sqlExecution.executeQueryAsync("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'", resultSet -> {

            try {

                while (resultSet.next()) {
                    String tableName = resultSet.getString("table_name");
                    this.databaseSections.put(tableName, new SQLDatabaseSection(databaseType, tableName, this.sqlExecution));
                }

            } catch (final SQLException exception) {
                exception.printStackTrace();
            }

            return true;
        }, true).get();

    }

    @Override
    public void shutdown() {
        this.sqlExecution.shutdown();
        this.databaseSections.clear();
    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {

        if (this.databaseSections.containsKey(name)) return this.databaseSections.get(name);

        final DatabaseSection databaseSection = new SQLDatabaseSection(this.databaseType, name, this.sqlExecution);
        this.databaseSections.put(name, databaseSection);

        return databaseSection;
    }

    @Override
    public void deleteSection(@NotNull String name) {
        this.sqlExecution.executeUpdate("DROP TABLE " + name);
        this.databaseSections.remove(name);
    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return Lists.newCopyOnWriteArrayList(this.databaseSections.values());
    }

    @Override
    public DatabaseSection getSection(@NotNull String name) {
        return this.databaseSections.get(name);
    }
}
