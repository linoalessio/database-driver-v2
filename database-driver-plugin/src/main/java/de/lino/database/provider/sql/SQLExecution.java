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

public class SQLExecution {

    private static final String ARGUMENTS = "jdbc:%s://%s:%d/%s?serverTimezone=UTC";
    private final HikariDataSource hikariDataSource;

    public SQLExecution(@NotNull DatabaseType databaseType, @NotNull Credentials credentials) {

        final HikariConfig hikariConfig = new HikariConfig();

        switch (databaseType) {

            case MY_SQL, POSTGRE_SQL, MARIA_DB, MONGO_DB, RETHINK_DB -> {
                hikariConfig.setJdbcUrl(String.format(ARGUMENTS, databaseType.getType(), credentials.getAddress(), credentials.getPort(), credentials.getDatabase()));
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case SQLITE -> {
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + credentials.getFileRepository());
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername(credentials.getUserName());
                hikariConfig.setPassword(credentials.getPassword());
            }
            case H2_DB -> {
                hikariConfig.setJdbcUrl("jdbc:h2:./" + credentials.getFileRepository());
                hikariConfig.setDriverClassName(databaseType.getDriverClass());
                hikariConfig.setUsername("sa");
                hikariConfig.setPassword("");
            }

        }

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

    public void shutdown() {
        this.hikariDataSource.close();
    }

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

    public <T> T executeQuery(@NotNull String query, Function<ResultSet, T> function, @NotNull T defaultValue, @NonNls Object... objects) {

        try (Connection connection = this.hikariDataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            int i = 1;
            for (Object object : objects) {
                if (object instanceof byte[]) preparedStatement.setBytes(i++, (byte[]) object);
                else preparedStatement.setObject(i++, object);
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                return function.apply(resultSet);
            } catch (final Throwable throwable) {
                return defaultValue;
            }

        } catch (final SQLException exception) {
            exception.printStackTrace();
        }

        return defaultValue;
    }

    public CompletableFuture<Void> executeUpdateAsync(@NotNull String query, @NonNls Object... objects) {
        return CompletableFuture.runAsync(() -> this.executeUpdate(query, objects));
    }

    public <T> CompletableFuture<T> executeQueryAsync(@NotNull String query, Function<ResultSet, T> function, @NotNull T defaultValue, @NonNls Object... objects) {
        return CompletableFuture.supplyAsync(() -> executeQuery(query, function, defaultValue, objects));
    }
}
