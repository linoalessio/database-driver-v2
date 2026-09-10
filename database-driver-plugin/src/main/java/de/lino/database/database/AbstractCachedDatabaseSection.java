package de.lino.database.database;

import com.google.common.collect.Maps;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * The single shared caching engine behind every {@link DatabaseSection} implementation shipped
 * by this module. Historically each backend section (SQL, MongoDB, RethinkDB, Redis, JSON file,
 * CSV file) carried its own copy of the identical pattern - an in-memory
 * {@code Map<String, DatabaseEntry>} filled once at construction, consulted by every read and
 * kept in sync by every write - which meant six drifting copies of the same cache logic, no
 * single place to change the caching strategy, and a heap floor that grew with the database
 * forever. This class owns that logic exactly once: a backend section only supplies its
 * <em>storage primitives</em> (the eight {@code protected abstract} methods below) and inherits
 * every {@link DatabaseSection} method from here, behaving according to the
 * {@link SectionConfig} it was constructed with:
 * <ul>
 *   <li>{@link CacheMode#FULL} / {@link CacheMode#LAZY} - a materialized in-memory view of the
 *       whole section, backed by a plain concurrent map. {@code FULL} is warmed by the owning
 *       provider's {@code createSection} right after construction (the historical timing);
 *       {@code LAZY} defers the same one-time load to the first data access, via
 *       {@link #ensureLoaded()}. A plain map rather than an unbounded {@link Cache} is
 *       deliberate: these modes' hot path is enumeration of a materialized table view, and the
 *       cache's per-entry future/entry wrappers would add real per-row overhead (its own
 *       documentation estimates 100-150 bytes each) for no benefit here.</li>
 *   <li>{@link CacheMode#BOUNDED} - a read-through, size-bounded (optionally expiring)
 *       {@link Cache} obtained through {@link Caches}, i.e. this library's own cache SPI, with
 *       {@link #fetchOne} as the loader. Concurrent misses for one id collapse into a single
 *       backend read (the cache's stampede protection), writes go through to the store and then
 *       refresh the cache, so the owning process always reads its own writes.</li>
 *   <li>{@link CacheMode#NONE} - no memory at all; every call is answered by a primitive.</li>
 * </ul>
 * Aggregate reads in {@code BOUNDED}/{@code NONE} ({@link #count()}, {@link #exists(String)},
 * {@link #getEntries()}) always go to the backing store - a partial cache can prove presence
 * but never absence.
 * <p>
 * <b>Concurrency contract:</b> best-effort, matching the historical sections - no new
 * guarantees. The map-backed modes claim inserted ids atomically ({@code putIfAbsent}), but a
 * {@link #reload()} racing a reader may briefly expose a partially repopulated view, exactly as
 * before; the remote-checked modes' insert precondition (a not-exists check followed by the
 * write) is best-effort by nature.
 */
public abstract class AbstractCachedDatabaseSection implements DatabaseSection {

    /**
     * This section's name (its table, collection, key prefix, directory or file name,
     * depending on the backend).
     */
    private final String name;

    /**
     * How this section holds entries in memory; fixed for the section's lifetime - the owning
     * provider replaces the whole instance when a caller re-declares a section under a
     * different configuration.
     */
    private final SectionConfig config;

    /**
     * The materialized view backing {@link CacheMode#FULL} and {@link CacheMode#LAZY}, keyed by
     * id; {@code null} in every other mode so a bounded or memory-less section cannot silently
     * accumulate entries here.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * The read-through cache backing {@link CacheMode#BOUNDED}; {@code null} in every other
     * mode. Only the {@link Cache} contract is used - never a concrete implementation class -
     * so whatever the {@link Caches} SPI provides at runtime slots in unchanged.
     */
    private final Cache<String, DatabaseEntry> cache;

    /**
     * Whether {@link #entries} currently reflects the backing store, for the map-backed modes'
     * one-time load: {@code volatile} for the {@link #ensureLoaded()} fast path, mutated only
     * under {@link #loadLock}.
     */
    private volatile boolean loaded;

    /**
     * Serializes the map-backed modes' load ({@link #ensureLoaded()}, {@link #reload()},
     * {@link #clear()}'s loaded-state flip) so concurrent first readers trigger exactly one
     * backend load instead of racing several.
     */
    private final Object loadLock = new Object();

    /**
     * Prepares the engine's per-mode backing state. Deliberately loads <em>nothing</em>, in
     * every mode: the concrete section's constructor must first initialize the storage handles
     * the primitives need (a connection pool, a collection, a directory path ...), and section
     * construction itself must stay cheap so providers can materialize sections on demand
     * without touching row data - the {@link CacheMode#FULL} warm-up is triggered by the owning
     * provider through {@link #warmUp()} instead.
     *
     * @param name   this section's name
     * @param config how this section holds entries in memory
     */
    protected AbstractCachedDatabaseSection(@NotNull String name, @NotNull SectionConfig config) {

        this.name = name;
        this.config = config;

        switch (config.cacheMode()) {

            case FULL, LAZY -> {
                this.entries = Maps.newConcurrentMap();
                this.cache = null;
            }
            case BOUNDED -> {
                this.entries = null;
                this.cache = Caches.newCache(this::loadEntry, config.ttl(), config.maxEntries());
                if (config.ttl() != null) DatabaseRepositoryRegistry.scheduleTtlSweeps(this.cache);
            }
            default -> {
                this.entries = null;
                this.cache = null;
            }

        }

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
     * any in-memory state. This is the point read behind {@link CacheMode#NONE}'s
     * {@link #findEntryById} and the loader behind {@link CacheMode#BOUNDED}'s cache; backends
     * without an efficient point lookup implement it as a bounded scan and document that cost
     * on their override.
     *
     * @param id the primary key to look up
     * @return the stored entry, or empty if the backing store holds no row under {@code id}
     */
    protected abstract Optional<DatabaseEntry> fetchOne(@NotNull String id);

    /**
     * Persists {@code databaseEntry} as a new row of the backing store. Storage only: the
     * duplicate check and the cache bookkeeping are the engine's job (see {@link #insert}),
     * so an implementation must not consult or modify any cache state here.
     *
     * @param databaseEntry the entry to persist
     */
    protected abstract void persistInsert(@NotNull DatabaseEntry databaseEntry);

    /**
     * Replaces the backing store's row under {@code databaseEntry}'s id with the given entry.
     * Storage only: the presence check and the cache bookkeeping are the engine's job
     * (see {@link #update}).
     *
     * @param databaseEntry the entry to persist over the existing row
     */
    protected abstract void persistUpdate(@NotNull DatabaseEntry databaseEntry);

    /**
     * Removes the backing store's row under {@code id}. Storage only: the presence check and
     * the cache bookkeeping are the engine's job (see {@link #delete}).
     *
     * @param id the primary key of the row to remove
     */
    protected abstract void persistDelete(@NotNull String id);

    /**
     * Counts the rows of the backing store natively (SQL {@code COUNT(*)}, Mongo
     * {@code countDocuments}, a file count ...), bypassing any in-memory state - how the
     * non-materialized modes answer {@link #count()}, since a partial cache cannot count what
     * it never held.
     *
     * @return the number of rows currently in the backing store
     */
    protected abstract long countRemote();

    /**
     * Checks natively whether the backing store holds a row under {@code id}, bypassing any
     * in-memory state - how the non-materialized modes answer {@link #exists(String)} and
     * guard their writes, since a partial cache can prove presence but never absence.
     *
     * @param id the primary key to check
     * @return {@code true} if the backing store holds a row under {@code id}
     */
    protected abstract boolean existsRemote(@NotNull String id);

    /**
     * Removes every row of the backing store (SQL {@code TRUNCATE}, Mongo {@code deleteMany},
     * a file wipe ...). Storage only: the engine resets its own cache state afterwards
     * (see {@link #clear()}).
     */
    protected abstract void clearRemote();

    /**
     * How this section holds entries in memory - the configuration it was constructed with,
     * exposed so the owning provider can decide whether a re-declared section may keep this
     * instance or must be replaced (see
     * {@link DatabaseProvider#createSection(String, SectionConfig)}).
     *
     * @return this section's cache configuration
     */
    public final @NotNull SectionConfig getConfig() {
        return this.config;
    }

    /**
     * Performs the one-time synchronous load for a {@link CacheMode#FULL} section; a no-op in
     * every other mode (and once already warm). Providers call this right after constructing a
     * {@code FULL} section from {@code createSection}, which is what preserves the historical
     * "warm by the time {@code createSection} returns" timing while keeping construction itself
     * free of row I/O - a {@code FULL} section merely <em>materialized</em> another way (e.g.
     * via {@code getSection}) stays cold until its first data access instead.
     */
    public final void warmUp() {
        if (this.config.cacheMode() == CacheMode.FULL) this.ensureLoaded();
    }

    /**
     * The engine's current in-memory view of {@code id}, for the rare storage primitive whose
     * on-disk format needs the <em>previous</em> row state to build the next one (see the JSON
     * store's merge-style {@code persistUpdate}). In the map-backed modes this preserves the
     * historical behavior exactly - the in-memory copy of an entry is not guaranteed
     * byte-identical to the stored one, and the in-memory copy is what the historical code
     * read; in every other mode it is empty by design (a bounded cache peek would have to load,
     * which a storage primitive must never trigger), so callers fall back to their own storage
     * read.
     *
     * @param id the primary key to look up
     * @return the cached entry, or empty if no in-memory view exists or it holds none
     */
    protected final Optional<DatabaseEntry> cachedEntry(@NotNull String id) {
        return this.entries == null ? Optional.empty() : Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Per mode: {@link CacheMode#FULL} discards the in-memory view and synchronously
     * re-populates it from the backing store via {@link #loadAll} (the historical behavior);
     * {@link CacheMode#LAZY} just discards it and lets the next data access pay the re-load;
     * {@link CacheMode#BOUNDED} invalidates the cache so every entry is re-read on next access;
     * {@link CacheMode#NONE} has nothing to discard and is a no-op - its reads never left the
     * backing store in the first place.
     */
    @Override
    public void reload() {

        switch (this.config.cacheMode()) {

            case FULL -> {
                synchronized (this.loadLock) {
                    this.entries.clear();
                    this.loadAll(entry -> this.entries.put(entry.getId(), entry));
                    this.loaded = true;
                }
            }
            case LAZY -> {
                synchronized (this.loadLock) {
                    this.entries.clear();
                    this.loaded = false;
                }
            }
            case BOUNDED -> this.cache.invalidateAll();
            case NONE -> { }

        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * In the map-backed modes the id is claimed in memory atomically ({@code putIfAbsent})
     * <em>before</em> {@link #persistInsert} runs, so two concurrent inserts of the same id
     * resolve to exactly one winner and one {@link DataAlreadyExist}. The non-materialized
     * modes can only offer a best-effort {@link #existsRemote} precondition instead - the
     * store, not this process, holds the truth there.
     */
    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new DataAlreadyExist(databaseEntry.getId());
                this.persistInsert(databaseEntry);
            }
            case BOUNDED -> {
                if (this.existsRemote(databaseEntry.getId())) throw new DataAlreadyExist(databaseEntry.getId());
                this.persistInsert(databaseEntry);
                this.cache.put(databaseEntry.getId(), databaseEntry);
            }
            case NONE -> {
                if (this.existsRemote(databaseEntry.getId())) throw new DataAlreadyExist(databaseEntry.getId());
                this.persistInsert(databaseEntry);
            }

        }

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                if (!this.entries.containsKey(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());
                this.persistUpdate(databaseEntry);
                this.entries.put(databaseEntry.getId(), databaseEntry);
            }
            case BOUNDED -> {
                if (!this.existsRemote(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());
                this.persistUpdate(databaseEntry);
                this.cache.put(databaseEntry.getId(), databaseEntry);
            }
            case NONE -> {
                if (!this.existsRemote(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());
                this.persistUpdate(databaseEntry);
            }

        }

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                if (!this.entries.containsKey(id)) throw new NoSuchEntryFound(id);
                this.persistDelete(id);
                this.entries.remove(id);
            }
            case BOUNDED -> {
                if (!this.existsRemote(id)) throw new NoSuchEntryFound(id);
                this.persistDelete(id);
                this.cache.invalidate(id);
            }
            case NONE -> {
                if (!this.existsRemote(id)) throw new NoSuchEntryFound(id);
                this.persistDelete(id);
            }

        }

    }

    @Override
    public long count() {

        return switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                yield this.entries.size();
            }
            case BOUNDED, NONE -> this.countRemote();

        };

    }

    /**
     * {@inheritDoc}
     * <p>
     * In every mode the backing store is wiped via {@link #clearRemote()} first; the map-backed
     * modes then mark themselves loaded-and-empty rather than unloaded - an empty section is a
     * perfectly valid loaded state, and re-reading a store known to be empty would be a wasted
     * load.
     */
    @Override
    public void clear() {

        switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.clearRemote();
                synchronized (this.loadLock) {
                    this.entries.clear();
                    this.loaded = true;
                }
            }
            case BOUNDED -> {
                this.clearRemote();
                this.cache.invalidateAll();
            }
            case NONE -> this.clearRemote();

        }

    }

    @Override
    public boolean exists(@NotNull String id) {

        return switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                yield this.entries.containsKey(id);
            }
            case BOUNDED, NONE -> this.existsRemote(id);

        };

    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {

        return switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                yield Optional.ofNullable(this.entries.get(id));
            }
            case BOUNDED -> this.boundedLookup(id);
            case NONE -> this.fetchOne(id);

        };

    }

    /**
     * {@inheritDoc}
     * <p>
     * Materializes the complete section as a list in every mode - the historical contract,
     * kept so existing consumers see no change. In {@link CacheMode#BOUNDED}/{@link CacheMode#NONE}
     * this streams the backing store into a fresh list on every call (the cache is never the
     * source: it could only contribute a partial, mixed-staleness view).
     */
    @Override
    public @UnmodifiableView List<DatabaseEntry> getEntries() {

        return switch (this.config.cacheMode()) {

            case FULL, LAZY -> {
                this.ensureLoaded();
                yield List.copyOf(this.entries.values());
            }
            case BOUNDED, NONE -> {
                final List<DatabaseEntry> collected = new ArrayList<>();
                this.loadAll(collected::add);
                yield List.copyOf(collected);
            }

        };

    }

    /**
     * The map-backed modes' one-time load: on the first data access (or the first after a
     * {@link CacheMode#LAZY} {@link #reload()}), streams the whole backing store into
     * {@link #entries} via {@link #loadAll}. Double-checked on the {@code volatile}
     * {@link #loaded} flag so the warm path costs one read, with {@link #loadLock} making
     * concurrent first readers share a single load.
     */
    private void ensureLoaded() {

        if (this.loaded) return;

        synchronized (this.loadLock) {

            if (this.loaded) return;

            this.entries.clear();
            this.loadAll(entry -> this.entries.put(entry.getId(), entry));
            this.loaded = true;

        }

    }

    /**
     * {@link CacheMode#BOUNDED}'s read path: a cache hit is served from memory, a miss runs
     * {@link #loadEntry} once even under concurrent misses (the cache's stampede protection)
     * and caches the result. The loader signals a missing row by failing with
     * {@link NoSuchEntryFound}, translated back to an empty {@link Optional} here; the
     * underlying cache does not retain failed loads, so absence is re-checked against the
     * backing store on every call (no negative caching) - an entry inserted by another process
     * becomes visible immediately.
     *
     * @param id the primary key to look up
     * @return the entry, or empty if the backing store holds no row under {@code id}
     */
    private Optional<DatabaseEntry> boundedLookup(@NotNull String id) {

        try {
            return Optional.of(this.cache.get(id).join());
        } catch (final CompletionException exception) {
            if (exception.getCause() instanceof NoSuchEntryFound) return Optional.empty();
            throw exception;
        }

    }

    /**
     * {@link CacheMode#BOUNDED}'s cache loader: a point read via {@link #fetchOne}, run
     * asynchronously so the cache's internal map operation never blocks on backend I/O. Fails
     * with {@link NoSuchEntryFound} for a missing row - the {@link Cache} contract forbids
     * {@code null} values, and a failed load is deliberately not cached (see
     * {@link #boundedLookup}).
     *
     * @param id the primary key to load
     * @return a future resolving to the stored entry, or failing with {@link NoSuchEntryFound}
     */
    private CompletableFuture<DatabaseEntry> loadEntry(@NotNull String id) {
        return CompletableFuture.supplyAsync(() -> this.fetchOne(id).orElseThrow(() -> new NoSuchEntryFound(id)));
    }

}
