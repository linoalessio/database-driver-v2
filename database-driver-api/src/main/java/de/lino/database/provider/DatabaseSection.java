package de.lino.database.provider;

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

import de.lino.database.provider.entity.DatabaseEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a single logical grouping of {@link DatabaseEntry} objects within a
 * {@link DatabaseProvider} (e.g. a SQL table, a MongoDB collection, a Redis key prefix or a
 * directory of JSON files) and exposes CRUD operations over its entries.
 * <p>
 * Every synchronous operation declared here has a corresponding {@code *Async} default method
 * that executes the same logic on the common {@link CompletableFuture} pool.
 */
public interface DatabaseSection {

    /**
     * Get the section's name.
     *
     * @return the section's name
     */
    String getName();

    /**
     * Insert a new json document into the database.
     *
     * @param databaseEntry the entry to insert
     */
    void insert(@NotNull DatabaseEntry databaseEntry);

    /**
     * Update an existing json document from the database.
     *
     * @param databaseEntry the entry to update
     */
    void update(@NotNull DatabaseEntry databaseEntry);

    /**
     * Delete an existing json document from the database.
     *
     * @param id primary key
     */
    void delete(@NotNull String id);

    /**
     * Count all existing json documents.
     *
     * @return the number of entries currently stored in this section
     */
    long count();

    /**
     * Clear this database section.
     */
    void clear();

    /**
     * Discards this section's own cached view of its entries and rebuilds it from the
     * backing store, picking up entries added, changed or removed by something other
     * than this section itself (e.g. a backup restored directly onto disk while this
     * section was already loaded).
     * <p>
     * Every implementation shipped by this module caches its entries in memory beyond
     * what each write already keeps in sync (see each implementation's own class-level
     * documentation), so a genuine re-read of the backing store is required here, not a
     * no-op - {@link #getEntries()} and friends would otherwise never reflect a change
     * made outside this section.
     */
    void reload();

    /**
     * Check whether a json document exists.
     *
     * @param id primary key
     * @return true, if json document can be found, otherwise false
     */
    boolean exists(@NotNull String id);

    /**
     * Find a matching json document from the database.
     *
     * @param id primary key
     * @return an {@link Optional} containing the matching {@link DatabaseEntry}, or empty if no
     * entry exists under the given id
     */
    Optional<DatabaseEntry> findEntryById(@NotNull String id);

    /**
     * Get an unmodifiable list of all database entities.
     *
     * @return an unmodifiable list of all entries currently stored in this section
     */
    @UnmodifiableView
    List<DatabaseEntry> getEntries();

    /**
     * Execute the {@link #insert(DatabaseEntry)} process async.
     *
     * @param databaseEntry the entry to insert
     * @return a {@link CompletableFuture} that completes once the entry has been inserted
     */
    default CompletableFuture<Void> insertAsync(@NotNull DatabaseEntry databaseEntry) {
        return CompletableFuture.runAsync(() -> insert(databaseEntry));
    }

    /**
     * Execute the {@link #update(DatabaseEntry)} process async.
     *
     * @param databaseEntry the entry to update
     * @return a {@link CompletableFuture} that completes once the entry has been updated
     */
    default CompletableFuture<Void> updateAsync(@NotNull DatabaseEntry databaseEntry) {
        return CompletableFuture.runAsync(() -> update(databaseEntry));
    }

    /**
     * Execute the {@link #delete(String)} process async.
     *
     * @param id primary key
     * @return a {@link CompletableFuture} that completes once the entry has been deleted
     */
    default CompletableFuture<Void> deleteAsync(@NotNull String id) {
        return CompletableFuture.runAsync(() -> delete(id));
    }

    /**
     * Execute the {@link #count()} process async.
     *
     * @return a {@link CompletableFuture} resolving to the number of entries currently stored in
     * this section
     */
    default CompletableFuture<Long> countAsync() {
        return CompletableFuture.supplyAsync(this::count);
    }

    /**
     * Execute the {@link #clear()} section process async.
     *
     * @return a {@link CompletableFuture} that completes once the section has been cleared
     */
    default CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(this::clear);
    }

    /**
     * Execute the {@link #exists(String)} process async.
     *
     * @param id primary key
     * @return a {@link CompletableFuture} resolving to {@code true} if the entry exists,
     * {@code false} otherwise
     */
    default CompletableFuture<Boolean> existsAsync(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> exists(id));
    }

    /**
     * Execute the {@link #findEntryById(String)} process async.
     *
     * @param id primary key
     * @return a {@link CompletableFuture} resolving to an {@link Optional} containing the
     * matching {@link DatabaseEntry}, or empty if none exists under the given id
     */
    default CompletableFuture<Optional<DatabaseEntry>> findEntryByIdAsync(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> findEntryById(id));
    }

    /**
     * Execute the {@link #getEntries()} process async.
     *
     * @return a {@link CompletableFuture} resolving to an unmodifiable list of all entries
     * currently stored in this section
     */
    default CompletableFuture<List<DatabaseEntry>> getEntriesAsync() {
        return CompletableFuture.supplyAsync(this::getEntries);
    }

    /**
     * Execute the {@link #reload()} process async.
     *
     * @return a {@link CompletableFuture} that completes once this section has been reloaded
     */
    default CompletableFuture<Void> reloadAsync() {
        return CompletableFuture.runAsync(this::reload);
    }

}
