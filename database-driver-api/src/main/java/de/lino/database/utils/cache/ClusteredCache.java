package de.lino.database.utils.cache;

import java.util.concurrent.CompletableFuture;

/**
 * Partitions entities across multiple {@link Cache} shards following the
 * Cassandra principle (consistent hashing + replication).
 * <p>
 * Conceptually, each "node" is a separate {@link Cache} instance that owns
 * part of the key space. A {@link ConsistentHashRing} decides which shards
 * are responsible for a given key, including replication across multiple shards.
 * <p>
 * <b>Honest note on scale:</b> an implementation of this interface may only
 * simulate Cassandra's partitioning principle within a single JVM. True
 * distribution across multiple physical machines additionally requires network
 * communication between JVM processes, with each shard running as its own
 * process on its own hardware — the routing principle stays identical, only
 * the access to a given node changes from a direct method call to a network call.
 * <p>
 * This interface lives in the API module; concrete implementations live in the
 * plugin module so consumers only ever depend on this contract.
 *
 * @param <ID> type of the key
 * @param <T>  type of the entity
 */
public interface ClusteredCache<ID, T> {

    /**
     * Reads an entity. Queries the primary shard (first replica node per the
     * ring) for the given key.
     *
     * @param id key to look up, must not be {@code null}
     * @return a future that completes with the entity, or exceptionally if the loader fails
     */
    CompletableFuture<T> get(ID id);

    /**
     * Writes a value to all replica shards responsible for this key (analogous
     * to Cassandra's replication factor). Expected to run the per-shard writes
     * in parallel, not sequentially.
     *
     * @param id    key to store under, must not be {@code null}
     * @param value value to store, must not be {@code null}
     * @return a future that completes once all replica writes have finished
     */
    CompletableFuture<Void> put(ID id, T value);

    /**
     * Removes a key from every replica shard responsible for it.
     *
     * @param id key to remove, must not be {@code null}
     */
    void invalidate(ID id);

    /**
     * @return the total number of cached entries across all shards; replicated
     *         entries are counted once per replica
     */
    long totalSize();

    /**
     * @return the number of shards this cache is partitioned into
     */
    int shardCount();
}
