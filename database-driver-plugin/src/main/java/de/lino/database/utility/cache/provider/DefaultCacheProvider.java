package de.lino.database.utility.cache.provider;

import de.lino.database.utility.cache.DefaultCache;
import de.lino.database.utility.cache.DefaultClusteredCache;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.CacheProvider;
import de.lino.database.utils.cache.provider.Caches;
import de.lino.database.utils.cache.ClusteredCache;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Default {@link CacheProvider}, backing {@link Caches} with {@link DefaultCache}
 * and {@link DefaultClusteredCache}. Registered as a service in
 * {@code META-INF/services/de.lino.database.utils.cache.provider.CacheProvider} so it is picked
 * up automatically by {@link java.util.ServiceLoader} once this module is on the classpath.
 */
public final class DefaultCacheProvider implements CacheProvider {

    @Override
    public <ID, T> Cache<ID, T> newCache(Function<ID, CompletableFuture<T>> loader, Duration ttl, long maxSize) {
        return new DefaultCache<>(loader, ttl, maxSize);
    }

    @Override
    public <ID, T> ClusteredCache<ID, T> newClusteredCache(int shardCount, int replicationFactor,
                                                           Function<ID, CompletableFuture<T>> loader,
                                                           Duration ttl, long maxSizePerShard) {
        return new DefaultClusteredCache<>(shardCount, replicationFactor, loader, ttl, maxSizePerShard);
    }
}
