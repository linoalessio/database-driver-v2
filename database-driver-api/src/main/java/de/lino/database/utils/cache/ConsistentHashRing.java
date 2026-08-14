package de.lino.database.utils.cache;

import lombok.NonNull;

import java.util.List;

/**
 * Consistent-hashing ring following the Cassandra/DynamoDB principle: routes
 * keys to nodes, and supports replica selection across multiple nodes.
 * <p>
 * Each physical node is expected to be assigned several "virtual nodes" on the
 * ring, which spreads load more evenly and avoids hotspots. A key is hashed
 * onto the ring and routed to the next node clockwise.
 * <p>
 * This interface lives in the API module; concrete implementations live in the
 * plugin module so consumers only ever depend on this contract.
 *
 * @param <NodeId> type used to identify a node
 */
public interface ConsistentHashRing<NodeId> {

    /**
     * Adds a node to the ring. Expected to be safe to call concurrently with lookups.
     *
     * @param node node to add, must not be {@code null}
     */
    void addNode(@NonNull NodeId node);

    /**
     * Removes a node and all of its virtual nodes from the ring. Expected to be
     * safe to call concurrently with lookups.
     *
     * @param node node to remove, must not be {@code null}
     */
    void removeNode(@NonNull NodeId node);

    /**
     * Returns the node responsible for a key.
     *
     * @param key key to route, must not be {@code null}
     * @return the node owning this key on the ring
     */
    NodeId nodeFor(@NonNull Object key);

    /**
     * Returns up to {@code replicationFactor} distinct nodes for a key (for
     * replication, analogous to Cassandra's replication factor).
     *
     * @param key               key to route, must not be {@code null}
     * @param replicationFactor desired number of distinct replica nodes
     * @return up to {@code replicationFactor} distinct nodes, in ring order starting at the key's position
     */
    List<NodeId> nodesFor(@NonNull Object key, int replicationFactor);
}
