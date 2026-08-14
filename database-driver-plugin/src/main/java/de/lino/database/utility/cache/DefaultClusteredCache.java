package de.lino.database.utility.cache;

import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.ClusteredCache;
import de.lino.database.utils.cache.ConsistentHashRing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Default implementation of {@link ClusteredCache}, partitioning entities
 * across multiple {@link DefaultCache} shards using {@link ConsistentHashRing}
 * for routing and replication, in pure Java with no external dependencies
 * beyond {@code java.*}.
 * <p>
 * <b>Honest note on scale:</b> this class simulates Cassandra's partitioning
 * principle WITHIN a single JVM. For real 10<sup>12</sup>-scale distribution across
 * multiple physical machines, network communication between JVM processes would
 * additionally be required (e.g. via {@code java.net.Socket} or
 * {@code java.net.http.HttpClient}, both already part of the JDK with no further
 * dependency) — each shard would then run as its own process on its own hardware.
 * The routing principle (this ring) stays identical; only access to a given node
 * would go over the network instead of a direct method call.
 *
 * @param <ID> key type
 * @param <T>  entity type
 */
public final class DefaultClusteredCache<ID, T> implements ClusteredCache<ID, T> {

    private final ConsistentHashRing<Integer> ring;
    private final ConcurrentHashMap<Integer, Cache<ID, T>> shards = new ConcurrentHashMap<>();
    private final int replicationFactor;

    /**
     * @param shardCount        number of simulated nodes (e.g. CPU core count, or —
     *                          in true distribution — machine count)
     * @param replicationFactor number of shards each key is simultaneously stored on
     * @param loader            supplies an entity if it is not cached on any shard
     * @param ttl               validity duration per entry
     * @param maxSizePerShard   size limit PER shard (total capacity = shardCount * maxSizePerShard)
     */
    public DefaultClusteredCache(int shardCount, int replicationFactor,
                                  Function<ID, CompletableFuture<T>> loader,
                                  Duration ttl, long maxSizePerShard) {
        if (shardCount < 1) throw new IllegalArgumentException("shardCount must be >= 1");
        if (replicationFactor < 1 || replicationFactor > shardCount) {
            throw new IllegalArgumentException("replicationFactor must be between 1 and shardCount");
        }
        Objects.requireNonNull(loader, "loader must not be null");

        this.replicationFactor = replicationFactor;

        List<Integer> nodeIds = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            nodeIds.add(i);
            shards.put(i, new DefaultCache<>(loader, ttl, maxSizePerShard));
        }
        this.ring = new DefaultConsistentHashRing<>(nodeIds);
    }

    @Override
    public CompletableFuture<T> get(ID id) {
        Objects.requireNonNull(id, "id must not be null");
        int primaryShard = ring.nodeFor(id);
        return shards.get(primaryShard).get(id);
    }

    @Override
    public CompletableFuture<Void> put(ID id, T value) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(value, "value must not be null");

        List<Integer> targetShards = ring.nodesFor(id, replicationFactor);
        CompletableFuture<?>[] writes = targetShards.stream()
                .map(shardId -> CompletableFuture.runAsync(() -> shards.get(shardId).put(id, value)))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(writes);
    }

    @Override
    public void invalidate(ID id) {
        Objects.requireNonNull(id, "id must not be null");
        for (int shardId : ring.nodesFor(id, replicationFactor)) {
            shards.get(shardId).invalidate(id);
        }
    }

    @Override
    public long totalSize() {
        return shards.values().stream().mapToLong(Cache::size).sum();
    }

    @Override
    public int shardCount() {
        return shards.size();
    }
}
