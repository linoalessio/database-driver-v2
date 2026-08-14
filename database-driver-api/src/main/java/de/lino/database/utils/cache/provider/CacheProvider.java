package de.lino.database.utils.cache.provider;

import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.ClusteredCache;

import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Service-provider interface that supplies concrete {@link Cache} and
 * {@link ClusteredCache} instances for {@link Caches}.
 * <p>
 * The API module only declares the {@link Cache} / {@link ClusteredCache}
 * contracts and cannot depend on their implementations, which live in a
 * separate module (e.g. database-driver-plugin). Implementations are
 * discovered at runtime via {@link ServiceLoader}: a provider module registers
 * its implementation by listing its fully-qualified class name in a
 * {@code META-INF/services/de.lino.database.utils.cache.provider.CacheProvider} resource file.
 */
public interface CacheProvider {

    /**
     * Creates a new {@link Cache} instance.
     *
     * @param loader  asynchronously supplies an entity when it is not (or no longer) cached
     * @param ttl     validity duration of an entry, {@code null} for unbounded
     * @param maxSize maximum number of entries; {@code <= 0} for unbounded
     * @param <ID>    type of the key
     * @param <T>     type of the cached entity
     * @return a new cache instance
     */
    <ID, T> Cache<ID, T> newCache(Function<ID, CompletableFuture<T>> loader, Duration ttl, long maxSize);

    /**
     * Creates a new {@link ClusteredCache} instance.
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
    <ID, T> ClusteredCache<ID, T> newClusteredCache(int shardCount, int replicationFactor,
                                                     Function<ID, CompletableFuture<T>> loader,
                                                     Duration ttl, long maxSizePerShard);
}
