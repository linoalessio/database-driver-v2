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
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The shared {@link DatabaseProvider} implementation behind every SQL vendor this driver
 * supports (MySQL, PostgreSQL, MariaDB, SQLite, H2, Oracle, Microsoft SQL Server, Apache Derby -
 * see the vendor-specific subclasses in the sibling packages), each {@link DatabaseSection}
 * mapping to one table, all sharing this provider's single {@link SQLExecution} connection pool.
 */
public class SQLDatabaseProvider implements DatabaseProvider {

    /**
     * The SQL vendor this provider is connected to, needed to pick the right table-listing
     * query in {@link #getPattern} and the right BLOB column type per {@link SQLDatabaseSection}.
     */
    private final DatabaseType databaseType;

    /**
     * The connection pool shared by this provider and every {@link SQLDatabaseSection} it creates.
     */
    private final SQLExecution sqlExecution;

    /**
     * Every registered section, keyed by table name.
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * Connects via {@code sqlExecution} and loads every existing table of {@code databaseType} as
     * a {@link SQLDatabaseSection}.
     *
     * @param databaseType  the SQL vendor being connected to
     * @param sqlExecution  the connection pool to run every query and update through
     */
    @SneakyThrows
    public SQLDatabaseProvider(@NotNull DatabaseType databaseType, @NotNull SQLExecution sqlExecution) {

        this.databaseType = databaseType;
        this.sqlExecution = sqlExecution;
        this.databaseSections = Maps.newConcurrentMap();

        this.reload();

    }

    @Override
    public void shutdown() {
        this.sqlExecution.shutdown();
        this.databaseSections.clear();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #databaseSections} entirely and rebuilds it with a fresh
     * {@link SQLDatabaseSection} per table currently reported by {@link #getPattern},
     * the same query the constructor itself runs.
     */
    @Override
    @SneakyThrows
    public void reload() {

        this.databaseSections.clear();
        String tablePattern = getPattern(this.databaseType);

        this.sqlExecution.executeQueryAsync(tablePattern, resultSet -> {

            try {

                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    this.databaseSections.put(tableName, new SQLDatabaseSection(this.databaseType, tableName, this.sqlExecution));
                }

            } catch (final SQLException exception) {
                exception.printStackTrace();
            }

            return true;
        }, true).get();

    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new SQLDatabaseSection(this.databaseType, key, this.sqlExecution));
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

    /**
     * {@link DatabaseSection#clear() Clears} every section concurrently rather than one table at
     * a time, since every section shares the same {@link SQLExecution} connection pool - which is
     * itself built for concurrent multi-threaded use - so clearing them in parallel is no less
     * safe than clearing them sequentially, just faster; overrides {@link DatabaseProvider}'s
     * default, which would otherwise run the whole sequential {@link #clear()} on a single
     * background thread.
     *
     * @return a {@link CompletableFuture} that completes once every section has been cleared
     */
    @Override
    public CompletableFuture<Void> clearAsync() {

        final List<CompletableFuture<Void>> pending = this.getSections().stream().map(DatabaseSection::clearAsync).toList();

        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRun(this.databaseSections::clear);

    }

    /**
     * Builds the vendor-specific query that lists every existing table's name as
     * {@code TABLE_NAME}, used by the constructor to load existing {@link SQLDatabaseSection}s.
     *
     * @param databaseType the SQL vendor to build a table-listing query for
     * @return the vendor-specific table-listing query
     */
    private static @NotNull String getPattern(@NotNull DatabaseType databaseType) {

        String tablePattern;

        switch (databaseType) {

            case SQLITE -> tablePattern = "SELECT name AS TABLE_NAME FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';";
            case APACHE_DERBY -> tablePattern = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='App' AND TABLE_NAME NOT LIKE 'sqlite_%';";
            case MICROSOFT_SQL_SERVER -> tablePattern = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='dbo'";
            case ORACLE -> tablePattern = "SELECT table_name AS TABLE_NAME FROM all_tables WHERE owner='SCHEMA_NAME'";
            default -> tablePattern = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'";

        }
        return tablePattern;
    }

}
