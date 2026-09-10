package de.lino.database.database;

import com.google.common.collect.Maps;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The single shared caching engine behind every {@link DatabaseSection} implementation shipped
 * by this module. Historically each backend section (SQL, MongoDB, RethinkDB, Redis, JSON file,
 * CSV file) carried its own copy of the identical pattern - an in-memory
 * {@code Map<String, DatabaseEntry>} filled once at construction, consulted by every read and
 * kept in sync by every write - which meant six drifting copies of the same cache logic and no
 * single place to ever change the caching strategy. This class owns that pattern exactly once;
 * a backend section now only supplies its <em>storage primitives</em> (the eight
 * {@code protected abstract} methods below) and inherits every {@link DatabaseSection} method
 * from here.
 * <p>
 * The engine currently preserves the historical behavior bit-for-bit: every entry of the
 * backing store is held in memory, loaded eagerly when the concrete section's constructor calls
 * {@link #reload()}, with reads served purely from memory and writes persisted first and then
 * mirrored into memory. Splitting the primitives out of the cache logic is what makes the
 * caching strategy changeable in one place without touching any backend again.
 * <p>
 * <b>Concurrency contract:</b> best-effort, matching the historical sections - the backing map
 * is concurrent, {@link #insert} claims its id atomically via {@code putIfAbsent}, but there is
 * no cross-method transactionality: a {@link #reload()} racing a reader may briefly expose a
 * partially repopulated view, exactly as before.
 */
public abstract class AbstractCachedDatabaseSection implements DatabaseSection {

    /**
     * This section's name (its table, collection, key prefix, directory or file name,
     * depending on the backend).
     */
    private final String name;

    /**
     * Every entry currently in this section, keyed by id and kept in sync with the backing
     * store by every write method; the source of truth for every read method.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * Prepares the engine's empty in-memory state. Deliberately does <em>not</em> load anything:
     * the concrete section's constructor must first initialize the storage handles the
     * primitives need (a connection pool, a collection, a directory path ...) and only then
     * trigger the initial load by calling {@link #reload()} as its last step - a load started
     * from this constructor would run before any subclass field exists.
     *
     * @param name this section's name
     */
    protected AbstractCachedDatabaseSection(@NotNull String name) {
        this.name = name;
        this.entries = Maps.newConcurrentMap();
    }

    /**
     * Streams every row of the backing store to {@code consumer}, without ever materializing
     * the whole table in the implementation itself (SQL backends page through the result set
     * with a bounded fetch size, cursor-based backends iterate their cursor, file backends walk
     * their directory or file line by line). The engine - not the backend - decides what to do
     * with the streamed entries, which is what keeps "read everything" usable even when the
     * destination is not an in-memory map.
     *
     * @param consumer called once per stored entry, in backend iteration order
     */
    protected abstract void loadAll(@NotNull Consumer<DatabaseEntry> consumer);

    /**
     * Reads the single row stored under {@code id} directly from the backing store, bypassing
     * any in-memory state. This is the point-read primitive future cache modes are built on;
     * backends without an efficient point lookup implement it as a bounded scan and document
     * that cost on their override.
     *
     * @param id the primary key to look up
     * @return the stored entry, or empty if the backing store holds no row under {@code id}
     */
    protected abstract Optional<DatabaseEntry> fetchOne(@NotNull String id);

    /**
     * Persists {@code databaseEntry} as a new row of the backing store. Storage only: the
     * duplicate check and the in-memory bookkeeping are the engine's job (see {@link #insert}),
     * so an implementation must not consult or modify any cache state here.
     *
     * @param databaseEntry the entry to persist
     */
    protected abstract void persistInsert(@NotNull DatabaseEntry databaseEntry);

    /**
     * Replaces the backing store's row under {@code databaseEntry}'s id with the given entry.
     * Storage only: the presence check and the in-memory bookkeeping are the engine's job
     * (see {@link #update}).
     *
     * @param databaseEntry the entry to persist over the existing row
     */
    protected abstract void persistUpdate(@NotNull DatabaseEntry databaseEntry);

    /**
     * Removes the backing store's row under {@code id}. Storage only: the presence check and
     * the in-memory bookkeeping are the engine's job (see {@link #delete}).
     *
     * @param id the primary key of the row to remove
     */
    protected abstract void persistDelete(@NotNull String id);

    /**
     * Counts the rows of the backing store natively (SQL {@code COUNT(*)}, Mongo
     * {@code countDocuments}, a file count ...), bypassing any in-memory state. Unused while a
     * section holds its full contents in memory, but the primitive every non-materialized cache
     * mode answers {@link #count()} with.
     *
     * @return the number of rows currently in the backing store
     */
    protected abstract long countRemote();

    /**
     * Checks natively whether the backing store holds a row under {@code id}, bypassing any
     * in-memory state - the point-check sibling of {@link #countRemote()}.
     *
     * @param id the primary key to check
     * @return {@code true} if the backing store holds a row under {@code id}
     */
    protected abstract boolean existsRemote(@NotNull String id);

    /**
     * Removes every row of the backing store (SQL {@code TRUNCATE}, Mongo {@code deleteMany},
     * a file wipe ...). Storage only: the engine clears its own in-memory state afterwards
     * (see {@link #clear()}).
     */
    protected abstract void clearRemote();

    /**
     * The engine's current in-memory view of {@code id}, for the rare storage primitive whose
     * on-disk format needs the <em>previous</em> row state to build the next one (see the JSON
     * store's merge-style {@code persistUpdate}). Reading the previous state from here rather
     * than via {@link #fetchOne} preserves the historical behavior exactly, because the
     * in-memory copy and the stored copy of an entry are not guaranteed to be identical for
     * every backend; callers must still fall back to {@link #fetchOne} for the case where this
     * view has nothing cached.
     *
     * @param id the primary key to look up
     * @return the cached entry, or empty if the in-memory view currently holds none
     */
    protected final Optional<DatabaseEntry> cachedEntry(@NotNull String id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards the in-memory view entirely and re-populates it from every row currently in the
     * backing store via {@link #loadAll}, the same load the concrete section's constructor
     * triggers - so an entry added, changed or removed directly in the backing store since this
     * section was constructed (e.g. a backup restored while the application was already
     * running) is picked up here even though ordinary reads never touch the store.
     */
    @Override
    public void reload() {
        this.entries.clear();
        this.loadAll(entry -> this.entries.put(entry.getId(), entry));
    }

    /**
     * {@inheritDoc}
     * <p>
     * The id is claimed in memory atomically ({@code putIfAbsent}) <em>before</em>
     * {@link #persistInsert} runs, so two concurrent inserts of the same id resolve to exactly
     * one winner and one {@link DataAlreadyExist} - a check-then-persist sequence could let
     * both through.
     */
    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new DataAlreadyExist(databaseEntry.getId());

        this.persistInsert(databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        this.persistUpdate(databaseEntry);
        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        this.persistDelete(id);
        this.entries.remove(id);

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {
        this.clearRemote();
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.containsKey(id);
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public @UnmodifiableView List<DatabaseEntry> getEntries() {
        return List.copyOf(this.entries.values());
    }

}
