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
import de.lino.database.provider.nosql.csv.CSVDatabaseProvider;
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
import java.util.concurrent.CompletableFuture;

/**
 * The concrete, process-wide {@link DatabaseRepository}: tracks every registered
 * {@link DatabaseProvider} by numeric id in a {@link java.util.concurrent.ConcurrentHashMap},
 * and knows how to construct one for each supported {@link DatabaseType}. Installs itself as
 * {@link DatabaseRepository}'s generated {@code getInstance()} accessor on construction, and as
 * a side effect of constructing a {@link DefaultFileProvider}, also installs the default
 * {@link de.lino.database.json.file.FileProvider}'s generated {@code getInstance()} accessor.
 * <p>
 * Every id-keyed mutation ({@link #registerDatabaseProvider}, {@link #unregisterDatabaseProvider})
 * goes through a single atomic map operation rather than a separate check-then-act pair, so
 * concurrent calls for the same id cannot race each other into double-registering or
 * double-shutting-down a provider.
 */
@Getter
public class DatabaseRepositoryRegistry extends DatabaseRepository {

    /**
     * Whether {@link #logBytes(String, JsonDocument)} prints anything; {@code volatile} since it
     * is set once from the constructor but read from arbitrary threads via every
     * {@code *DatabaseSection} implementation.
     */
    private static volatile boolean LOG_BYTES = false;

    /**
     * Every registered provider, keyed by its caller-assigned id, each entry pairing the
     * provider with the {@link DatabaseType} it was created for. Backed by a
     * {@link java.util.concurrent.ConcurrentHashMap} (via {@link Maps#newConcurrentMap()}) so
     * reads never block, and so {@link #registerDatabaseProvider} and
     * {@link #unregisterDatabaseProvider} can mutate it atomically per id.
     */
    private final Map<Integer, Pair<DatabaseType, DatabaseProvider>> databaseProviders;

    /**
     * Installs this instance as {@link DatabaseRepository}'s generated {@code getInstance()}
     * accessor and, as a side effect of constructing a {@link DefaultFileProvider}, installs the
     * default {@link de.lino.database.json.file.FileProvider}'s generated {@code getInstance()}
     * accessor too - {@code FileProvider}'s own {@code setInstance} is only accessible to its
     * own subclasses, so this indirection is the only way to trigger it from here.
     *
     * @param logBytes whether {@link #logBytes(String, JsonDocument)} should print anything
     */
    public DatabaseRepositoryRegistry(final boolean logBytes) {

        setInstance(this);
        LOG_BYTES = logBytes;

        this.databaseProviders = Maps.newConcurrentMap();
        new DefaultFileProvider();

    }

    /**
     * Prints {@code document}'s serialized size in bytes under {@code message}, if
     * {@link #LOG_BYTES} is enabled; a no-op otherwise, checked before {@code document} is ever
     * serialized so disabled logging costs nothing beyond the flag check.
     *
     * @param message  a {@code %d}-format string describing what is being measured
     * @param document the document whose serialized size is measured
     */
    public static void logBytes(@NotNull final String message, @NotNull final JsonDocument document) {
        if (!LOG_BYTES) return;
        System.out.printf(message + "%n", document.toBytes().length);
    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool() {
        return this.databaseProviders.values().stream().map(Pair::second).toList();
    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool(@NotNull final DatabaseType databaseType) {
        return this.databaseProviders.values().stream()
                .filter(pair -> pair.first() == databaseType)
                .map(Pair::second)
                .toList();
    }

    @Override
    public void shutdown() {

        this.databaseProviders.forEach((key, value) -> {
            try {
                value.second().shutdown();
                System.out.println("Database Provider with id #" + key + " (" + value.first() + ") successfully unregistered");
            } catch (final RuntimeException exception) {
                System.err.println("Database Provider with id #" + key + " (" + value.first() + ") failed to shut down cleanly:");
                exception.printStackTrace();
            }
        });

        this.databaseProviders.clear();

    }

    /**
     * {@link #shutdown() Shuts down} every registered provider concurrently rather than one at a
     * time, since each provider owns its own, independent connection and shutting one down is
     * typically I/O-bound (closing a socket, flushing a client) - overriding
     * {@link DatabaseRepository}'s default, which would otherwise just run the whole sequential
     * {@link #shutdown()} on a single background thread.
     *
     * @return a {@link CompletableFuture} that completes once every provider has been shut down
     */
    @Override
    public CompletableFuture<Void> shutdownAsync() {

        final List<CompletableFuture<Void>> pending = this.databaseProviders.values().stream()
                .map(databaseTypeDatabaseProviderPair -> databaseTypeDatabaseProviderPair.second().shutdownAsync())
                .toList();

        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRun(this.databaseProviders::clear);

    }

    @Override
    public Pair<DatabaseProvider, DatabaseProvider> convert(final int sourceId, final int targetId) {

        final Pair<DatabaseType, DatabaseProvider> sourcePair = this.databaseProviders.get(sourceId);
        if (sourcePair == null) throw new IllegalStateException("@DatabaseRepositoryRegistry.convert: Database Provider with id #" + sourceId + " does not exist");

        final Pair<DatabaseType, DatabaseProvider> targetPair = this.databaseProviders.get(targetId);
        if (targetPair == null) throw new IllegalStateException("@DatabaseRepositoryRegistry.convert: Database Provider with id #" + targetId + " does not exist");

        final DatabaseType sourceType = sourcePair.first();
        final DatabaseProvider source = sourcePair.second();
        final DatabaseProvider destination = targetPair.second();

        source.getSections().forEach(section -> {

            if (sourceType == DatabaseType.REDIS) {

                final String sectionName = section.getName().split(":")[0];
                final DatabaseSection databaseSection = destination.createSection(sectionName);
                section.getEntries().forEach(databaseSection::insert);

            } else {

                if (destination.existsSection(section.getName())) destination.deleteSection(section.getName());
                final DatabaseSection databaseSection = destination.createSection(section.getName());
                section.getEntries().forEach(databaseSection::insert);

            }

        });

        System.out.println("Database Provider with id #" + sourceId + " (" + sourceType + ") successfully converted to database provider with id #" + targetId + " (" + targetPair.first() + ")");
        return new Pair<>(source, destination);
    }

    @Override
    public Optional<DatabaseProvider> findDatabaseProviderById(final int id) {
        final Pair<DatabaseType, DatabaseProvider> pair = this.databaseProviders.get(id);
        return pair == null ? Optional.empty() : Optional.of(pair.second());
    }

    @Override
    public DatabaseProvider registerDatabaseProvider(final int id, @NotNull final DatabaseType databaseType, @NotNull final Credentials credentials) {

        final Pair<DatabaseType, DatabaseProvider> registered = this.databaseProviders.compute(id, (key, existing) -> {
            if (existing != null) throw new IllegalStateException("@DatabaseRepositoryRegistry.registerDatabaseProvider: Provider with id #" + id + " already exists");
            return new Pair<>(databaseType, createProvider(databaseType, credentials));
        });

        System.out.println("Database Provider with id #" + id + " (" + databaseType + ") successfully registered");
        return registered.second();
    }

    @Override
    public DatabaseProvider unregisterDatabaseProvider(final int id) {

        final Pair<DatabaseType, DatabaseProvider> pair = this.databaseProviders.remove(id);
        if (pair == null) throw new IllegalStateException("@DatabaseRepositoryRegistry.unregisterDatabaseProvider: Database Provider with id #" + id + " does not exist");

        final DatabaseProvider unregistered = pair.second();
        unregistered.shutdown();
        System.out.println("Database Provider with id #" + id + " (" + pair.first() + ") successfully unregistered");

        return unregistered;

    }

    /**
     * Constructs a fresh {@link DatabaseProvider} for {@code databaseType}, connected with
     * {@code credentials}. Extracted out of {@link #registerDatabaseProvider} so that method's
     * {@link Map#compute} remapping function - which, per {@link java.util.concurrent.ConcurrentHashMap}'s
     * contract, should stay short - is just this one call plus the existence check.
     *
     * @param databaseType the database type to construct a provider for
     * @param credentials  the login credentials to connect with
     * @return the newly constructed, already-connected {@link DatabaseProvider}
     */
    @NotNull
    private static DatabaseProvider createProvider(@NotNull final DatabaseType databaseType, @NotNull final Credentials credentials) {
        return switch (databaseType) {

            case MY_SQL -> new MySQLDatabaseProvider(credentials);
            case MARIA_DB -> new MariaDBDatabaseProvider(credentials);
            case POSTGRES_SQL -> new PostgreSQLDatabaseProvider(credentials);
            case ORACLE -> new OracleSQLDatabaseProvider(credentials);
            case MICROSOFT_SQL_SERVER -> new MicrosoftSQLServerDatabaseProvider(credentials);
            case APACHE_DERBY -> new ApacheDerbyDatabaseProvider(credentials);

            case SQLITE -> new SQLiteDatabaseProvider(credentials);
            case H2_DB -> new H2DatabaseProvider(credentials);

            case MONGO_DB -> new MongoDBDatabaseProvider(credentials);
            case RETHINK_DB -> new RethinkDBDatabaseProvider(credentials);
            case REDIS -> new RedisDatabaseProvider(credentials);

            case JSON -> new JsonDatabaseProvider(credentials);
            case CSV ->  new CSVDatabaseProvider(credentials);

        };
    }

}
