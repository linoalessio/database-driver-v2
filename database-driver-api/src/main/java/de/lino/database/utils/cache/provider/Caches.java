package de.lino.database.utils.cache.provider;

import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.ClusteredCache;
import lombok.NonNull;

import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Entry point for creating {@link Cache} and {@link ClusteredCache} instances
 * without the caller depending on any concrete implementation class.
 * <p>
 * The actual implementation (e.g. database-driver-plugin's in-memory cache) is
 * discovered at runtime via {@link ServiceLoader}, through a registered
 * {@link CacheProvider}. The discovered provider is cached after the first
 * lookup, so repeated calls do not re-scan the classpath.
 */
public final class Caches {

    private static volatile CacheProvider provider;

    private Caches() {
    }

    /**
     * Creates a new {@link Cache}.
     *
     * @param loader  asynchronously supplies an entity when it is not (or no longer) cached
     * @param ttl     validity duration of an entry, {@code null} for unbounded
     * @param maxSize maximum number of entries; {@code <= 0} for unbounded
     * @param <ID>    type of the key
     * @param <T>     type of the cached entity
     * @return a new cache instance
     */
    public static <ID, T> Cache<ID, T> newCache(@NonNull Function<ID, CompletableFuture<T>> loader,
                                                 Duration ttl, long maxSize) {
        return provider().newCache(loader, ttl, maxSize);
    }

    /**
     * Creates a new, unbounded {@link Cache}.
     *
     * @param loader asynchronously supplies an entity when it is not (or no longer) cached
     * @param ttl    validity duration of an entry, {@code null} for unbounded
     * @param <ID>   type of the key
     * @param <T>    type of the cached entity
     * @return a new cache instance
     */
    public static <ID, T> Cache<ID, T> newCache(@NonNull Function<ID, CompletableFuture<T>> loader, Duration ttl) {
        return newCache(loader, ttl, -1);
    }

    /**
     * Creates a new {@link ClusteredCache}.
     *
     * @param shardCount        number of shards to partition the key space into
     * @param replicationFactor number of shards each key is simultaneously stored on
     * @param loader            supplies an entity if it is not cached on any shard
     * @param ttl                validity duration per entry
     * @param maxSizePerShard   size limit per shard
     * @param <ID>              type of the key
     * @param <T>               type of the cached entity
     * @return a new clustered cache instance
     */
    public static <ID, T> ClusteredCache<ID, T> newClusteredCache(int shardCount, int replicationFactor,
                                                                   @NonNull Function<ID, CompletableFuture<T>> loader,
                                                                   Duration ttl, long maxSizePerShard) {
        return provider().newClusteredCache(shardCount, replicationFactor, loader, ttl, maxSizePerShard);
    }

    private static CacheProvider provider() {

        CacheProvider result = provider;

        if (result == null) {
            synchronized (Caches.class) {
                result = provider;
                if (result == null) {
                    result = ServiceLoader.load(CacheProvider.class)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No " + CacheProvider.class.getSimpleName() + " implementation found on the "
                                            + "classpath. Add a module providing META-INF/services/"
                                            + CacheProvider.class.getName() + " (e.g. database-driver-plugin) "
                                            + "as a dependency."));
                    provider = result;
                }
            }
        }

        return result;
    }

}
