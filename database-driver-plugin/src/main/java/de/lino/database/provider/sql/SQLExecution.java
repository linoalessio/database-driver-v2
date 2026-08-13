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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.lino.database.configuration.Credentials;
import de.lino.database.provider.DatabaseType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A single JDBC connection pool (via HikariCP) shared by every {@link SQLDatabaseSection} of one
 * {@link de.lino.database.provider.DatabaseProvider}, plus the parameterized query/update
 * helpers built on top of it. {@link HikariDataSource} is itself thread-safe and designed for
 * concurrent multi-threaded use, so every method here is safe to call concurrently without
 * additional locking.
 */
public class SQLExecution {

    /**
     * JDBC URL template shared by every vendor that connects over a plain
     * {@code host:port/database} address (MySQL, PostgreSQL, MariaDB); other vendors build their
     * own URL in {@link #getHikariConfig}.
     */
    private static final String ARGUMENTS = "jdbc:%s://%s:%d/%s?serverTimezone=UTC";

    /**
     * The underlying connection pool every query and update runs against.
     */
    private final HikariDataSource hikariDataSource;

    /**
     * Builds a connection pool for {@code databaseType}, configured with {@code credentials}.
     *
     * @param databaseType the SQL vendor to connect to
     * @param credentials  the login credentials and connection details to connect with
     */
    public SQLExecution(@NotNull DatabaseType databaseType, @NotNull Credentials credentials) {

        final HikariConfig hikariConfig = this.getHikariConfig(databaseType, credentials);

        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

        this.hikariDataSource = new HikariDataSource(hikariConfig);
    }

    /**
     * Closes the underlying connection pool. No further queries or updates should be issued
     * after this returns.
     */
    public void shutdown() {
        this.hikariDataSource.close();
    }

    /**
     * Runs a parameterized {@code INSERT}/{@code UPDATE}/{@code DELETE}/DDL statement, binding
     * each of {@code objects} in order (as raw bytes for {@code byte[]}, via
     * {@link PreparedStatement#setObject} otherwise).
     *
     * @param query   the parameterized SQL statement to execute
     * @param objects the values to bind, in placeholder order
     */
    public void executeUpdate(@NotNull String query, @NonNls Object... objects) {

        try (Connection connection = this.hikariDataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            int i = 1;
            for (Object object : objects) {
                if (object instanceof byte[]) preparedStatement.setBytes(i++, (byte[]) object);
                else preparedStatement.setObject(i++, object);
            }

            preparedStatement.executeUpdate();

        } catch (final SQLException exception) {
            exception.printStackTrace();
        }

    }

    /**
     * Runs a parameterized {@code SELECT} statement, binding each of {@code objects} in order,
     * and maps the resulting {@link ResultSet} through {@code function}.
     *
     * @param <T>          the type {@code function} maps the result set to
     * @param query        the parameterized SQL query to execute
     * @param function     maps the query's result set to the returned value; its own unchecked
     *                     exceptions are caught and treated the same as a failed query
     * @param defaultValue the value returned if the query fails, or if {@code function} throws
     * @param objects      the values to bind, in placeholder order
     * @return {@code function}'s result, or {@code defaultValue} if the query or {@code function} failed
     */
    public <T> T executeQuery(@NotNull String query, @NotNull Function<ResultSet, T> function, @NotNull T defaultValue, @NonNls Object... objects) {

        try (Connection connection = this.hikariDataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            int i = 1;
            for (Object object : objects) {
                if (object instanceof byte[]) preparedStatement.setBytes(i++, (byte[]) object);
                else preparedStatement.setObject(i++, object);
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                return function.apply(resultSet);
            } catch (final RuntimeException exception) {
                return defaultValue;
            }

        } catch (final SQLException exception) {
            exception.printStackTrace();
        }

        return defaultValue;
    }

    /**
     * Execute the {@link #executeUpdate(String, Object...)} process async.
     *
     * @param query   the parameterized SQL statement to execute
     * @param objects the values to bind, in placeholder order
     * @return a {@link CompletableFuture} that completes once the statement has run
     */
    public CompletableFuture<Void> executeUpdateAsync(@NotNull String query, @NonNls Object... objects) {
        return CompletableFuture.runAsync(() -> this.executeUpdate(query, objects));
    }

    /**
     * Execute the {@link #executeQuery(String, Function, Object, Object...)} process async.
     *
     * @param <T>          the type {@code function} maps the result set to
     * @param query        the parameterized SQL query to execute
     * @param function     maps the query's result set to the returned value
     * @param defaultValue the value the future resolves to if the query or {@code function} fails
     * @param objects      the values to bind, in placeholder order
     * @return a {@link CompletableFuture} resolving to {@code function}'s result, or
     * {@code defaultValue} if the query or {@code function} failed
     */
    public <T> CompletableFuture<T> executeQueryAsync(@NotNull String query, @NotNull Function<ResultSet, T> function, @NotNull T defaultValue, @NonNls Object... objects) {
        return CompletableFuture.supplyAsync(() -> executeQuery(query, function, defaultValue, objects));
    }

    /**
     * Builds the {@link HikariConfig} for {@code databaseType}: shared pool sizing and prepared
     * statement caching, plus a vendor-specific JDBC URL and driver class.
     *
     * @param databaseType the SQL vendor to build a configuration for
     * @param credentials  the login credentials and connection details to connect with
     * @return the built configuration, ready to open a {@link HikariDataSource} with
     */
    private @NotNull HikariConfig getHikariConfig(@NotNull DatabaseType databaseType, @NotNull Credentials credentials) {

        final HikariConfig hikariConfig = new HikariConfig();

        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setConnectionTimeout(30_000);
        hikariConfig.setMaxLifetime(1_800_000);

        switch (databaseType) {

            case MY_SQL, POSTGRES_SQL, MARIA_DB, MONGO_DB, RETHINK_DB -> {
                hikariConfig.setJdbcUrl(String.format(ARGUMENTS, databaseType.getType(), credentials.getAddress(), credentials.getPort(), credentials.getDatabase()));
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case SQLITE -> {
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + credentials.getFileRepository() + ".sqlite");
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case H2_DB -> {
                hikariConfig.setJdbcUrl("jdbc:h2:./" + credentials.getFileRepository());
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case ORACLE -> {
                hikariConfig.setJdbcUrl("jdbc:" + databaseType.getType() + "://" + credentials.getAddress() + ":" + credentials.getPort() + "/" + credentials.getDatabase());
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case MICROSOFT_SQL_SERVER -> {
                hikariConfig.setJdbcUrl("jdbc:" + databaseType.getType() + "://" + credentials.getAddress() + ":" + credentials.getPort() + ";databaseName=" + credentials.getDatabase());
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case APACHE_DERBY -> {
                hikariConfig.setJdbcUrl("jdbc:" + databaseType.getType() + ":" + credentials.getDatabase() + ";create=true");
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }

        }

        return hikariConfig;
    }

}
