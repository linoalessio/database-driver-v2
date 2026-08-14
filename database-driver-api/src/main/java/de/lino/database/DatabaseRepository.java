package de.lino.database;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.utils.Pair;
import lombok.Getter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Central entry point for managing the pool of registered {@link DatabaseProvider} instances.
 * <p>
 * A single {@link DatabaseRepository} instance is expected to exist per application and is
 * exposed through the generated {@code getInstance()} accessor once a concrete subclass has
 * installed itself via {@link #setInstance(DatabaseRepository)}. Implementations are responsible
 * for tracking
 * registered providers by numeric id, creating new providers for a given {@link DatabaseType}
 * and {@link Credentials}, and converting data between two registered providers.
 * <p>
 * Every synchronous operation declared here has a corresponding {@code *Async} default method
 * that executes the same logic on the common {@link CompletableFuture} pool.
 */
public abstract class DatabaseRepository {

    /**
     * The globally accessible instance of this repository, installed via
     * {@link #setInstance(DatabaseRepository)} and exposed through the generated
     * {@code getInstance()} accessor.
     */
    @Getter
    @Nullable
    private static DatabaseRepository instance;

    /**
     * Installs the given repository as the globally accessible instance returned by the
     * generated {@code getInstance()} accessor.
     *
     * @param instance the repository instance to install
     */
    protected static void setInstance(@NotNull DatabaseRepository instance) {
        DatabaseRepository.instance = instance;
    }

    /**
     * Get an unmodifiable list of all registered database.
     *
     * @return an unmodifiable {@code List} of every registered {@link DatabaseProvider}
     */
    @UnmodifiableView
    public abstract List<DatabaseProvider> getDatabaseProviderPool();

    /**
     * Get an unmodifiable list of all registered database by a specific type.
     *
     * @param databaseType the database type to filter by
     * @return an unmodifiable {@code List} of every registered {@link DatabaseProvider} of the
     * given type
     */
    @UnmodifiableView
    public abstract List<DatabaseProvider> getDatabaseProviderPool(@NotNull DatabaseType databaseType);

    /**
     * Shutdown all running database providers.
     */
    public abstract void shutdown();

    /**
     * Convert the content of a specific database to another one.
     *
     * @param sourceId Id of the database that shall be used as a resource database
     * @param targetId Id of the database that will be used as a destination database
     * @return a {@link Pair} of the two providers involved, the first one being the source, the
     * second one being the destination
     */
    public abstract Pair<DatabaseProvider, DatabaseProvider> convert(int sourceId, int targetId);

    /**
     * Get a specific database by id.
     *
     * @param id database id
     * @return an {@link Optional} containing the matching {@link DatabaseProvider}, or empty if
     * no database is registered under the given id
     */
    public abstract Optional<DatabaseProvider> findDatabaseProviderById(int id);

    /**
     * Register a new database.
     *
     * @param id           Id of the database
     * @param databaseType database type
     * @param credentials  login credentials
     * @return the newly created and registered {@link DatabaseProvider}
     */
    public abstract DatabaseProvider registerDatabaseProvider(int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials);

    /**
     * Shutdown a specific database and unregister it from the repository.
     *
     * @param id database id
     * @return the {@link DatabaseProvider} that was shut down and unregistered
     */
    public abstract DatabaseProvider unregisterDatabaseProvider(int id);

    /**
     * Execute the {@link #getDatabaseProviderPool()} process async.
     *
     * @return a {@link CompletableFuture} resolving to an unmodifiable list of every registered
     * {@link DatabaseProvider}
     */
    @UnmodifiableView
    public CompletableFuture<List<DatabaseProvider>> getDatabaseProviderPoolAsync() {
        return CompletableFuture.supplyAsync(this::getDatabaseProviderPool);
    }

    /**
     * Execute the {@link #getDatabaseProviderPool(DatabaseType)} process async.
     *
     * @param databaseType the database type to filter by
     * @return a {@link CompletableFuture} resolving to an unmodifiable list of every registered
     * {@link DatabaseProvider} of the given type
     */
    @UnmodifiableView
    public CompletableFuture<List<DatabaseProvider>> getDatabaseProviderPoolAsync(@NotNull DatabaseType databaseType) {
        return CompletableFuture.supplyAsync(() -> this.getDatabaseProviderPool(databaseType));
    }

    /**
     * Execute the {@link #shutdown()} process async.
     *
     * @return a {@link CompletableFuture} that completes once every database has been shut down
     */
    public CompletableFuture<Void> shutdownAsync() {
        return CompletableFuture.runAsync(this::shutdown);
    }

    /**
     * Execute the {@link #convert(int, int)} process async.
     *
     * @param sourceId Id of the database that shall be used as a resource database
     * @param targetId Id of the database that will be used as a destination database
     * @return a {@link CompletableFuture} resolving to a {@link Pair} of the source and
     * destination providers
     */
    public CompletableFuture<Pair<DatabaseProvider, DatabaseProvider>> convertAsync(int sourceId, int targetId) {
        return CompletableFuture.supplyAsync(() -> convert(sourceId, targetId));
    }

    /**
     * Execute the {@link #findDatabaseProviderById(int)} process async.
     *
     * @param id database id
     * @return a {@link CompletableFuture} resolving to an {@link Optional} containing the
     * matching {@link DatabaseProvider}, or empty if none is registered under the given id
     */
    @SneakyThrows
    public CompletableFuture<Optional<DatabaseProvider>> findDatabaseProviderByIdAsync(int id) {
        return CompletableFuture.supplyAsync(() -> findDatabaseProviderById(id));
    }

    /**
     * Execute the {@link #registerDatabaseProvider(int, DatabaseType, Credentials)} process
     * async.
     *
     * @param id           Id of the database
     * @param databaseType database type
     * @param credentials  login credentials
     * @return a {@link CompletableFuture} resolving to the newly created and registered
     * {@link DatabaseProvider}
     */
    public CompletableFuture<DatabaseProvider> registerDatabaseProviderAsync(int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials) {
        return CompletableFuture.supplyAsync(() -> registerDatabaseProvider(id, databaseType, credentials));
    }

    /**
     * Execute the {@link #unregisterDatabaseProvider(int)} process async.
     *
     * @param id database id
     * @return a {@link CompletableFuture} resolving to the {@link DatabaseProvider} that was shut
     * down and unregistered
     */
    public CompletableFuture<DatabaseProvider> unregisterDatabaseProviderAsync(int id) {
        return CompletableFuture.supplyAsync(() -> unregisterDatabaseProvider(id));
    }

}
