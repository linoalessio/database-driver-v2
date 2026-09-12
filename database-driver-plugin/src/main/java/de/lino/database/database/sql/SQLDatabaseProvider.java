package de.lino.database.database.sql;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.SectionConfig;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The shared {@link DatabaseProvider} implementation behind every SQL vendor this driver
 * supports (MySQL, PostgreSQL, MariaDB, SQLite, H2, Oracle, Microsoft SQL Server, Apache Derby -
 * see the vendor-specific subclasses in the sibling packages), each {@link DatabaseSection}
 * mapping to one table, all sharing this database's single {@link SQLExecution} connection pool.
 * Section lifecycle and caching live in {@link AbstractLazyDatabaseProvider}; this class only
 * supplies the vendor-aware storage operations - listing tables, constructing a
 * {@link SQLDatabaseSection}, dropping a table.
 */
public class SQLDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * The SQL vendor this database is connected to, needed to pick the right table-listing
     * query in {@link #getPattern} and the right BLOB column type per {@link SQLDatabaseSection}.
     */
    private final DatabaseType databaseType;

    /**
     * The connection pool shared by this database and every {@link SQLDatabaseSection} it creates.
     */
    private final SQLExecution sqlExecution;

    /**
     * Connects via {@code sqlExecution} and discovers every existing table's name of
     * {@code databaseType}. Only names - no section objects, no row data - so construction
     * cost is one table-listing query, independent of how much the database holds.
     *
     * @param databaseType  the SQL vendor being connected to
     * @param sqlExecution  the connection pool to run every query and update through
     */
    public SQLDatabaseProvider(@NotNull DatabaseType databaseType, @NotNull SQLExecution sqlExecution) {

        this.databaseType = databaseType;
        this.sqlExecution = sqlExecution;

        this.reload();

    }

    /**
     * The connection pool this database runs every query and update through - exposed (since
     * 1.3.16) as the deliberate raw-SQL escape hatch for a consumer feature that genuinely
     * cannot be expressed over {@link de.lino.database.database.DatabaseSection}'s
     * key/value surface (the motivating case: a Postgres {@code tsvector}/GIN full-text search
     * index, which needs vendor-specific column types and index DDL no generic section can
     * carry). Callers share this pool with every section of this database: never call
     * {@link SQLExecution#shutdown()} on it - the provider owns its lifecycle - and keep
     * statements short-lived so section traffic is never starved of pooled connections.
     *
     * @return the shared connection pool
     */
    @NotNull
    public SQLExecution getSqlExecution() {
        return this.sqlExecution;
    }

    @Override
    public void shutdown() {
        this.sqlExecution.shutdown();
        this.forgetSections();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Runs {@link #getPattern}'s vendor-specific table-listing query, streaming each
     * {@code TABLE_NAME} to {@code consumer}.
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        this.sqlExecution.executeQuery(getPattern(this.databaseType), resultSet -> {

            try {

                while (resultSet.next()) consumer.accept(resultSet.getString("TABLE_NAME"));

            } catch (final SQLException exception) {
                exception.printStackTrace();
            }

            return true;
        }, true);

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new SQLDatabaseSection(this.databaseType, name, this.sqlExecution, config);
    }

    @Override
    protected void dropSectionRemote(@NotNull String name) {
        this.sqlExecution.executeUpdate("DROP TABLE " + name);
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

        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRun(this::forgetSections);

    }

    /**
     * Builds the vendor-specific query that lists every existing table's name as
     * {@code TABLE_NAME}, used by {@link #discoverNames} to enumerate this database's sections.
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
