package de.lino.database;


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
import de.lino.database.configuration.Credentials;
import de.lino.database.file.DefaultFileProvider;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import de.lino.database.provider.nosql.json.JsonDatabaseProvider;
import de.lino.database.provider.nosql.mongodb.MongoDBDatabaseProvider;
import de.lino.database.provider.nosql.redis.RedisDatabaseProvider;
import de.lino.database.provider.nosql.rethinkdb.RethinkDBDatabaseProvider;
import de.lino.database.provider.sql.derby.ApacheDerbyDatabaseProvider;
import de.lino.database.provider.sql.h2db.H2DatabaseProvider;
import de.lino.database.provider.sql.mariadb.MariaDBDatabaseProvider;
import de.lino.database.provider.sql.microsoft.MicrosoftSQLServerDatabaseProvider;
import de.lino.database.provider.sql.mysql.MySQLDatabaseProvider;
import de.lino.database.provider.sql.orcale.OracleSQLDatabaseProvider;
import de.lino.database.provider.sql.postgresql.PostgreSQLDatabaseProvider;
import de.lino.database.provider.sql.sqlite.SQLiteDatabaseProvider;
import de.lino.database.utils.Pair;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
public class DatabaseRepositoryRegistry extends DatabaseRepository {

    private static boolean LOG_BYTES = false;
    private final Map<Integer, Pair<DatabaseType, DatabaseProvider>> databaseProviders;

    public DatabaseRepositoryRegistry(boolean logBytes) {

        setInstance(this);
        LOG_BYTES = logBytes;

        this.databaseProviders = Maps.newConcurrentMap();
        new DefaultFileProvider();

    }

    public static void logBytes(String message, JsonDocument document) {
        if (!LOG_BYTES) return;
        System.out.println(String.format(message, document.toBytes().length));
    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool() {
        return this.databaseProviders.values().stream().map(Pair::second).toList();
    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool(@NotNull DatabaseType databaseType) {
        return this.databaseProviders.values().stream()
                .filter(pair -> pair.first().equals(databaseType))
                .map(Pair::second)
                .toList();
    }

    @Override
    public void shutdown() {

        this.databaseProviders.forEach((key, value) -> {
            value.second().shutdown();
            System.out.println("Database Provider with id #" + key + " (" + value.first() + ") successfully unregistered");
        });

        this.databaseProviders.clear();

    }

    @Override
    public Pair<DatabaseProvider, DatabaseProvider> convert(@NotNull int sourceId, @NotNull int targetId) {

        final DatabaseType sourceType = this.databaseProviders.get(sourceId).first();
        final DatabaseProvider source = this.databaseProviders.get(sourceId).second();
        final DatabaseProvider destination = this.databaseProviders.get(targetId).second();

        source.getSections().forEach(section -> {

            if (sourceType.equals(DatabaseType.REDIS)) {

                final String sectionName = section.getName().split(":")[0];
                final DatabaseSection databaseSection = destination.createSection(sectionName);
                section.getEntries().forEach(databaseSection::insert);

            } else {

                if (destination.existsSection(section.getName())) destination.deleteSection(section.getName());
                final DatabaseSection databaseSection = destination.createSection(section.getName());
                section.getEntries().forEach(databaseSection::insert);

            }

        });

        System.out.println("Database Provider with id #" + sourceId + " (" + sourceType + ") successfully converted to database provider with id #" + targetId + " (" + this.databaseProviders.get(targetId).first() + ")");
        return new Pair<>(source, destination);
    }

    @Override
    public Optional<DatabaseProvider> findDatabaseProviderById(@NotNull int id) {
        final Pair<DatabaseType, DatabaseProvider> pair = this.databaseProviders.get(id);
        return pair == null ? Optional.empty() : Optional.of(pair.second());
    }

    @Override
    public DatabaseProvider registerDatabaseProvider(@NotNull int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials) {

        if (this.databaseProviders.containsKey(id)) throw new IllegalStateException("Database Provider with id #" + id + " already exists");

        final DatabaseProvider databaseProvider = switch (databaseType) {

            case MY_SQL -> new MySQLDatabaseProvider(credentials);
            case JSON -> new JsonDatabaseProvider(credentials);
            case H2_DB -> new H2DatabaseProvider(credentials);
            case MONGO_DB -> new MongoDBDatabaseProvider(credentials);
            case POSTGRE_SQL -> new PostgreSQLDatabaseProvider(credentials);
            case SQLITE -> new SQLiteDatabaseProvider(credentials);
            case MARIA_DB -> new MariaDBDatabaseProvider(credentials);
            case RETHINK_DB -> new RethinkDBDatabaseProvider(credentials);
            case ORACLE -> new OracleSQLDatabaseProvider(credentials);
            case MICROSOFT_SQL_SERVER -> new MicrosoftSQLServerDatabaseProvider(credentials);
            case APACHE_DERBY -> new ApacheDerbyDatabaseProvider(credentials);
            case REDIS -> new RedisDatabaseProvider(credentials);

        };

        this.databaseProviders.put(id, new Pair<>(databaseType, databaseProvider));
        System.out.println("Database Provider with id #" + id + " (" + databaseType + ") successfully registered");
        return databaseProvider;
    }

    @Override
    public DatabaseProvider unregisterDatabaseProvider(@NotNull int id) {

        final Pair<DatabaseType, DatabaseProvider> pair = this.databaseProviders.get(id);
        if (pair == null) throw new IllegalStateException("Database Provider with id #" + id + " does not exist");

        final DatabaseProvider unregistered = pair.second();
        unregistered.shutdown();
        this.databaseProviders.remove(id);
        System.out.println("Database Provider with id #" + id + " (" + pair.first() + ") successfully unregistered");

        return unregistered;

    }

}
