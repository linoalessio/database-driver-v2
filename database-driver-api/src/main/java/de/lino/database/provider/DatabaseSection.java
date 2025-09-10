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

public interface DatabaseSection {

    /**
     * Get the section's name
     * @return name
     */
    String getName();

    /**
     * Insert a new json document into the database
     * @param databaseEntry: DatabaseEntry object
     */
    void insert(@NotNull DatabaseEntry databaseEntry);

    /**
     * Update an existing json document from the database
     * @param databaseEntry: DatabaseEntry object
     */
    void update(@NotNull DatabaseEntry databaseEntry);

    /**
     * Delete an existing json document from the database
     * @param id: primary key
     */
    void delete(@NotNull String id);

    /**
     * Count all existing json documents
     * @return
     */
    long count();

    /**
     * Delete this database section
     */
    void delete();

    /**
     * Check whether a json document exists
     * @param id: primary key
     * @return true, if json document can be found, otherwise false
     */
    boolean exists(@NotNull String id);

    /**
     * Find a matching json document from the database
     * @param id: primary key
     * @return Optional<DatabaseEntry>
     */
    Optional<DatabaseEntry> findEntryById(@NotNull String id);

    /**
     * Get an unmodifiable list of all database entities
     * @return
     */
    @UnmodifiableView
    List<DatabaseEntry> getEntries();

    /**
     * Execute insert process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> insertAsync(@NotNull DatabaseEntry databaseEntry) {
        return CompletableFuture.runAsync(() -> insert(databaseEntry));
    }

    /**
     * Execute update process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> updateAsync(@NotNull DatabaseEntry databaseEntry) {
        return CompletableFuture.runAsync(() -> update(databaseEntry));
    }

    /**
     * Execute delete process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> deleteAsync(@NotNull String id) {
        return CompletableFuture.runAsync(() -> delete(id));
    }

    /**
     * Execute count process async
     * @return CompletableFuture, type Long
     */
    default CompletableFuture<Long> countAsync() {
        return CompletableFuture.supplyAsync(this::count);
    }

    /**
     * Execute delete section process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> deleteAsync() {
        return CompletableFuture.runAsync(this::delete);
    }

    /**
     * Execute exists process async
     * @return CompletableFuture, type Boolean
     */
    default CompletableFuture<Boolean> existsAsync(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> exists(id));
    }

    /**
     * Execute find entry process async
     * @return CompletableFuture, type Optional<JsonDocument>
     */
    default CompletableFuture<Optional<DatabaseEntry>> findEntryByIdAsync(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> findEntryById(id));
    }

    /**
     * Execute getEntries process async
     * @return CompletableFuture, type List<DatabaseEntry>
     */
    default CompletableFuture<List<DatabaseEntry>> getEntriesAsync() {
        return CompletableFuture.supplyAsync(this::getEntries);
    }

}
