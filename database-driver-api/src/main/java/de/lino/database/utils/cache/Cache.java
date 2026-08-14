package de.lino.database.utils.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generic, thread-safe, asynchronous cache for entities of type {@code T},
 * keyed by an identifier of type {@code ID}.
 * <p>
 * <b>Complexity (expected of a well-behaved implementation):</b>
 * <ul>
 *   <li>{@link #get}: O(1) amortized (hash lookup).</li>
 *   <li>{@link #put} / {@link #invalidate}: O(1) amortized.</li>
 *   <li>{@link #evictExpired()}: O(n) — a full scan, so it should be called
 *       periodically (e.g. every 60s via a scheduler) rather than on the hot path.</li>
 * </ul>
 * <p>
 * <b>Design intent:</b>
 * <ul>
 *   <li><b>Stampede protection:</b> concurrent requests for the same, not-yet-cached
 *       key should trigger the loader only once; all callers share the same future.</li>
 *   <li><b>Async:</b> {@link #get} must never block; the loader supplies a
 *       {@link CompletableFuture} that the caller can chain freely.</li>
 *   <li><b>Single implementation module:</b> this interface lives in the API module;
 *       concrete implementations (e.g. an in-memory, TTL/size-bounded cache) live in the
 *       plugin module so consumers only ever depend on this contract.</li>
 * </ul>
 *
 * @param <ID> type of the key (e.g. Long, UUID, String)
 * @param <T>  type of the cached entity
 */
public interface Cache<ID, T> {

    /**
     * Returns the entity for the given key — from the cache if present and not
     * expired, otherwise loaded via the configured loader. Must never block.
     *
     * @param id key to look up, must not be {@code null}
     * @return a future that completes with the entity, or exceptionally if the loader fails
     */
    CompletableFuture<T> get(ID id);

    /**
     * Writes a value directly into the cache, e.g. after a successful save,
     * bypassing the loader.
     *
     * @param id    key to store under, must not be {@code null}
     * @param value value to store, must not be {@code null}
     */
    void put(ID id, T value);

    /**
     * Removes a single entry from the cache, if present.
     *
     * @param id key to remove, must not be {@code null}
     */
    void invalidate(ID id);

    /**
     * Removes all entries from the cache.
     */
    void invalidateAll();

    /**
     * Removes all expired entries. This is a full scan and should be invoked
     * periodically rather than on every request.
     */
    void evictExpired();

    /**
     * @return the current number of entries held by this cache, including
     *         entries that are in the process of loading
     */
    int size();

    /**
     * Takes a snapshot of the currently loaded, non-expired entries. Intended
     * for debugging and metrics, not for hot-path use.
     *
     * @return an immutable-in-spirit map view of key to value at the time of the call
     */
    Map<ID, T> snapshot();
}
