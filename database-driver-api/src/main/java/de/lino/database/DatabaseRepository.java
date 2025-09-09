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

import de.lino.database.configuration.Credentials;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseType;
import de.lino.database.utils.Pair;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class DatabaseRepository {

    @Getter
    private static DatabaseRepository instance;

    protected static void setInstance(DatabaseRepository instance) {
        DatabaseRepository.instance = instance;
    }

    /**
     * Get an unmodifiable list of all registered database provider
     * @return a List
     */
    @UnmodifiableView
    public abstract List<DatabaseProvider> getDatabaseProviderPool();

    /**
     * Get an unmodifiable list of all registered database provider by a specific type
     * @return a List
     */
    @UnmodifiableView
    public abstract List<DatabaseProvider> getDatabaseProviderPool(@NotNull DatabaseType databaseType);

    /**
     * Shutdown all running database providers
     */
    public abstract void shutdown();

    /**
     * Convert the content of a specific database provider to another one
     * @param sourceId: Id of the database provider that shall be used as a resource database
     * @param targetId: Id of the database provider that will be used as a destination database
     * @return Pair<DatabaseProvider, DatabaseProvider>, first one is source, second destinatio
     */
    public abstract Pair<DatabaseProvider, DatabaseProvider> convert(@NotNull int sourceId, @NotNull int targetId);

    /**
     * Get a specific database provider by id
     * @param id: database provider id
     * @return Optional<DatabaseProvider>
     */
    public abstract Optional<DatabaseProvider> findDatabaseProviderById(@NotNull int id);

    /**
     * Register a new database provider
     * @param id: Id of the database
     * @param databaseType: database type
     * @param credentials: login credentials
     */
    public abstract DatabaseProvider registerDatabaseProvider(@NotNull int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials);

    /**
     * Shutdown a specific database provider and unregister it from the repository
     * @param id: database provider id
     */
    public abstract DatabaseProvider unregisterDatabaseProvider(@NotNull int id);


    /**
     * Execute the getDatabaseProviderPool process async
     * @return CompletableFuture, type List<DatabaseProvider>
     */
    @UnmodifiableView
    public CompletableFuture<List<DatabaseProvider>> getDatabaseProviderPoolAsync() {
        return CompletableFuture.supplyAsync(this::getDatabaseProviderPool);
    }

    /**
     * Execute the getDatabaseProviderPool(Type) process async
     * @return CompletableFuture, type List<DatabaseProvider>
     */
    @UnmodifiableView
    public CompletableFuture<List<DatabaseProvider>> getDatabaseProviderPoolAsync(@NonNull DatabaseType databaseType) {
        return CompletableFuture.supplyAsync(() -> this.getDatabaseProviderPool(databaseType));
    }

    /**
     * Execute the shutdown process async
     * @return CompletableFuture, type Void
     */
    public CompletableFuture<Void> shutdownAsync() {
        return CompletableFuture.runAsync(this::shutdown);
    }

    /**
     * Execute the convert process async
     * @return CompletableFuture, type BiConsumer<DatabaseProvider, DatabaseProvider>
     */
    public CompletableFuture<Pair<DatabaseProvider, DatabaseProvider>> convertAsync(@NotNull int sourceId, @NotNull int targetId) {
        return CompletableFuture.supplyAsync(() -> convert(sourceId, targetId));
    }

    /**
     * Execute the find database provider by id process async
     * @return CompletableFuture, type Optional<DatabaseProvider>
     */
    @SneakyThrows
    public CompletableFuture<Optional<DatabaseProvider>> findDatabaseProviderByIdAsync(int id) {
        return CompletableFuture.supplyAsync(() -> findDatabaseProviderById(id));
    }

    /**
     * Execute the register process async
     * @return CompletableFuture, type Void
     */
    public CompletableFuture<DatabaseProvider> registerDatabaseProviderAsync(@NotNull int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials) {
        return CompletableFuture.supplyAsync(() -> registerDatabaseProvider(id, databaseType, credentials));
    }

    /**
     * Execute the unregister process async
     * @return CompletableFuture, type Void
     */
    public CompletableFuture<DatabaseProvider> unregisterDatabaseProviderAsync(@NotNull int id) {
        return CompletableFuture.supplyAsync(() -> unregisterDatabaseProvider(id));
    }

}
