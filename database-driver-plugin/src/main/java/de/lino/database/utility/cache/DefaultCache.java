package de.lino.database.utility.cache;

import de.lino.database.utils.cache.Cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Default, in-memory implementation of {@link Cache}, backed by a
 * {@link ConcurrentHashMap}.
 * <p>
 * <b>Complexity:</b>
 * <ul>
 *   <li>{@link #get}: O(1) amortized (hash lookup in {@link ConcurrentHashMap}).
 *       Worst case O(n) only under pathological hash collisions — practically irrelevant.</li>
 *   <li>{@link #put} / {@link #invalidate}: O(1) amortized.</li>
 *   <li>{@link #evictExpired()}: O(n) — a full scan, so it should NOT be called on the
 *       hot path but periodically (e.g. every 60s via a scheduler).</li>
 *   <li>Size-based eviction (when {@code maxSize} is set): O(k) with a constant
 *       sample factor k (default 8), <b>independent of n</b> — no O(n) scan on every
 *       insert. This mirrors Redis' "approximated LRU": instead of locating the exact
 *       oldest entry (O(n), or O(log n) with an extra data structure), a small random
 *       sample is compared and the oldest candidate within it is evicted. That keeps
 *       the insert path constant-time even with very many entries.</li>
 * </ul>
 * <p>
 * <b>Scaling (clustering / volume):</b>
 * {@link ConcurrentHashMap} itself is unproblematic up to roughly 10<sup>7</sup>–10<sup>8</sup>
 * entries (segmented locking, no global lock on reads/writes). In practice the limiting
 * factor is the heap, not the algorithm:
 * <ul>
 *   <li>~10<sup>4</sup>–10<sup>6</sup> entries: fine with a default JVM heap, no {@code maxSize} needed.</li>
 *   <li>~10<sup>6</sup>–10<sup>7</sup> entries: use {@code maxSize} plus sample-based eviction to bound
 *       growth; a rough overhead estimate is 100–150 bytes per entry on top of the entity
 *       itself (map node + entry wrapper + {@link CompletableFuture}) — so ~1–1.5 GB of pure
 *       overhead at 10<sup>7</sup> entries.</li>
 *   <li>&gt;10<sup>8</sup> entries, or the need for a cache shared across multiple JVM instances
 *       ("real" clustering): this in-memory cache is not designed for that — an external,
 *       distributed cache (e.g. Redis, Hazelcast, Infinispan) is recommended instead, since a
 *       single JVM heap instance would otherwise suffer from GC pauses.</li>
 * </ul>
 * <p>
 * <b>Further design decisions:</b>
 * <ul>
 *   <li><b>Stampede protection:</b> {@link ConcurrentHashMap#compute} ensures that under
 *       concurrent requests for the same key, the loader runs only ONCE — all callers
 *       share the same future.</li>
 *   <li><b>Async:</b> {@link #get} never blocks; the loader supplies a
 *       {@link CompletableFuture} that the caller can chain freely.</li>
 *   <li><b>Usability:</b> a single read method ({@code get}); TTL and {@code maxSize}
 *       are optional and configurable per cache instance.</li>
 *   <li><b>Null-safety:</b> required parameters are checked via
 *       {@link Objects#requireNonNull} at the top of each method.</li>
 * </ul>
 *
 * @param <ID> type of the key (e.g. Long, UUID, String)
 * @param <T>  type of the cached entity
 */
public final class DefaultCache<ID, T> implements Cache<ID, T> {

    private static final int EVICTION_SAMPLE_SIZE = 8;

    private final ConcurrentHashMap<ID, CompletableFuture<Entry<T>>> store = new ConcurrentHashMap<>();
    private final Function<ID, CompletableFuture<T>> loader;
    private final Duration ttl;
    private final long maxSize; // <= 0 means unbounded

    private static final class Entry<T> {

        final T value;
        final Instant expiresAt; // null = never expires
        final AtomicLong lastAccessNanos = new AtomicLong(System.nanoTime());

        Entry(T value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        void touch() {
            lastAccessNanos.set(System.nanoTime());
        }

    }

    /**
     * @param loader  asynchronously supplies an entity when it is not (or no longer) cached
     * @param ttl     validity duration of an entry, {@code null} for unbounded
     * @param maxSize maximum number of entries; {@code <= 0} for unbounded.
     *                Approximate-LRU eviction (O(k)) is triggered once this is exceeded.
     */
    public DefaultCache(Function<ID, CompletableFuture<T>> loader, Duration ttl, long maxSize) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.ttl = ttl;
        this.maxSize = maxSize;
    }

    /**
     * @param loader asynchronously supplies an entity when it is not (or no longer) cached
     * @param ttl    validity duration of an entry, {@code null} for unbounded
     */
    public DefaultCache(Function<ID, CompletableFuture<T>> loader, Duration ttl) {
        this(loader, ttl, -1);
    }

    @Override
    public CompletableFuture<T> get(ID id) {
        Objects.requireNonNull(id, "id must not be null");

        CompletableFuture<Entry<T>> future = store.compute(id, (key, existing) -> {
            if (existing != null && !isFailedOrExpired(existing)) {
                return existing;
            }
            return loader.apply(key).thenApply(value -> {
                Objects.requireNonNull(value, "loader must not return null values");
                return new Entry<>(value, ttl == null ? null : Instant.now().plus(ttl));
            });
        });

        maybeEvictOverCapacity();

        return future.thenApply(entry -> {
            entry.touch(); // update access time for approximate LRU
            return entry.value;
        });
    }

    private boolean isFailedOrExpired(CompletableFuture<Entry<T>> future) {
        if (!future.isDone()) {
            return false; // still loading — piggyback instead of restarting
        }
        if (future.isCompletedExceptionally()) {
            return true;
        }
        return future.join().isExpired();
    }

    @Override
    public void put(ID id, T value) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(value, "value must not be null");
        store.put(id, CompletableFuture.completedFuture(
                new Entry<>(value, ttl == null ? null : Instant.now().plus(ttl))));
        maybeEvictOverCapacity();
    }

    @Override
    public void invalidate(ID id) {
        store.remove(Objects.requireNonNull(id));
    }

    @Override
    public void invalidateAll() {
        store.clear();
    }

    @Override
    public void evictExpired() {
        store.entrySet().removeIf(e -> isFailedOrExpired(e.getValue()));
    }

    /**
     * Approximate-LRU eviction once {@code maxSize} is exceeded: draws a constant
     * sample ({@value #EVICTION_SAMPLE_SIZE} entries) instead of scanning the whole
     * cache, and evicts the least-recently-used entry within it. O(k), independent
     * of the cache size n — keeping the insert path constant-time even at 10^6+ entries.
     */
    private void maybeEvictOverCapacity() {
        if (maxSize <= 0 || store.size() <= maxSize) {
            return;
        }
        ID oldestKey = null;
        long oldestAccess = Long.MAX_VALUE;
        int sampled = 0;

        for (Map.Entry<ID, CompletableFuture<Entry<T>>> e : store.entrySet()) {
            if (sampled++ >= EVICTION_SAMPLE_SIZE) {
                break;
            }
            CompletableFuture<Entry<T>> f = e.getValue();
            if (f.isDone() && !f.isCompletedExceptionally()) {
                long access = f.join().lastAccessNanos.get();
                if (access < oldestAccess) {
                    oldestAccess = access;
                    oldestKey = e.getKey();
                }
            }
        }
        if (oldestKey != null) {
            store.remove(oldestKey);
        }
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public Map<ID, T> snapshot() {
        Map<ID, T> result = new ConcurrentHashMap<>();
        store.forEach((id, future) -> {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                Entry<T> entry = future.join();
                if (!entry.isExpired()) {
                    result.put(id, entry.value);
                }
            }
        });
        return result;
    }
}
