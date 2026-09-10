package de.lino.database.database;

import de.lino.database.database.entity.DatabaseEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
     * <p>
     * The whole section is materialized as one list, whatever its size - kept that way for
     * compatibility. A consumer working through a large section should prefer
     * {@link #forEachEntry(Consumer)} (constant memory) or {@link #getEntries(long, int)}
     * (one bounded page at a time) instead of holding every entry at once.
     *
     * @return an unmodifiable list of all entries currently stored in this section
     */
    @UnmodifiableView
    List<DatabaseEntry> getEntries();

    /**
     * Streams every entry of this section to {@code consumer}, one at a time, without
     * materializing the section as a whole - the constant-memory alternative to
     * {@link #getEntries()} for sections too large to hold in one list. No entry order is
     * guaranteed.
     * <p>
     * This {@code default} implementation exists only so {@link DatabaseSection}
     * implementations written before this method keep compiling - it simply iterates
     * {@link #getEntries()} and therefore still materializes everything. Every section shipped
     * by this library's plugin module overrides it with a genuinely streaming implementation.
     *
     * @param consumer called once per stored entry
     */
    default void forEachEntry(@NotNull Consumer<DatabaseEntry> consumer) {
        this.getEntries().forEach(consumer);
    }

    /**
     * A single page of this section's entries: the entries at positions
     * {@code [offset, offset + limit)} of a stable, id-ordered enumeration - the same page for
     * the same arguments as long as the data does not change, so a consumer can work through a
     * large section chunk by chunk without ever holding more than one page.
     * <p>
     * This {@code default} implementation exists only so {@link DatabaseSection}
     * implementations written before this method keep compiling - it slices
     * {@link #getEntries()} (in that list's order) and therefore still materializes
     * everything. Every section shipped by this library's plugin module overrides it with an
     * implementation that pushes the paging toward the backing store instead.
     *
     * @param offset how many entries of the enumeration to skip; must not be negative
     * @param limit  the most entries the page may hold; must not be negative
     * @return the page's entries, empty once {@code offset} lies beyond the section's end
     */
    default @UnmodifiableView List<DatabaseEntry> getEntries(long offset, int limit) {

        if (offset < 0) throw new IllegalArgumentException("@DatabaseSection.getEntries: offset must not be negative, got " + offset);
        if (limit < 0) throw new IllegalArgumentException("@DatabaseSection.getEntries: limit must not be negative, got " + limit);

        final List<DatabaseEntry> all = this.getEntries();
        if (limit == 0 || offset >= all.size()) return List.of();

        return List.copyOf(all.subList((int) offset, (int) Math.min(all.size(), offset + limit)));

    }

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
     * Execute the {@link #forEachEntry(Consumer)} process async.
     *
     * @param consumer called once per stored entry, on the async pool's thread
     * @return a {@link CompletableFuture} that completes once every entry has been consumed
     */
    default CompletableFuture<Void> forEachEntryAsync(@NotNull Consumer<DatabaseEntry> consumer) {
        return CompletableFuture.runAsync(() -> forEachEntry(consumer));
    }

    /**
     * Execute the {@link #getEntries(long, int)} process async.
     *
     * @param offset how many entries of the enumeration to skip; must not be negative
     * @param limit  the most entries the page may hold; must not be negative
     * @return a {@link CompletableFuture} resolving to the page's entries
     */
    default CompletableFuture<List<DatabaseEntry>> getEntriesAsync(long offset, int limit) {
        return CompletableFuture.supplyAsync(() -> getEntries(offset, limit));
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
