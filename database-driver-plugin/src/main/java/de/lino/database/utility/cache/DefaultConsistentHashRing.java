package de.lino.database.utility.cache;

import de.lino.database.utils.cache.ConsistentHashRing;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Default implementation of {@link ConsistentHashRing}, implemented in pure
 * Java (only {@link java.security.MessageDigest} for SHA-256, part of the JDK).
 * <p>
 * Each physical node is assigned several "virtual nodes" on the ring, which
 * spreads load more evenly and avoids hotspots. A key is hashed onto the ring
 * and routed to the next node clockwise.
 * <p>
 * <b>Complexity:</b> {@link #nodesFor} is O(log V) for the ring lookup
 * (V = total number of virtual nodes) plus O(R) for replica selection —
 * independent of the number of stored keys n. That matters for scaling: whether
 * there are 10<sup>3</sup> or 10<sup>12</sup> keys, routing itself stays equally fast.
 * <p>
 * <b>Thread-safety:</b> the ring is backed by a {@link ConcurrentSkipListMap} so
 * that {@link #addNode} / {@link #removeNode} can safely run concurrently with
 * lookups (e.g. while rebalancing a live cluster), and the per-thread cached
 * {@link MessageDigest} in {@link #hash} avoids repeating the relatively costly
 * algorithm lookup on every call on this hot path.
 *
 * @param <NodeId> type used to identify a node
 */
public final class DefaultConsistentHashRing<NodeId> implements ConsistentHashRing<NodeId> {

    private static final int VIRTUAL_NODES_PER_NODE = 100;

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // cannot happen on a standard JVM
        }
    });

    private final NavigableMap<Long, NodeId> ring = new ConcurrentSkipListMap<>();

    /**
     * @param nodes initial set of nodes to place on the ring, must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code nodes} is empty
     */
    public DefaultConsistentHashRing(@NonNull List<NodeId> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("at least one node is required");
        }
        for (NodeId node : nodes) {
            addNode(node);
        }
    }

    @Override
    public void addNode(@NonNull NodeId node) {
        for (int i = 0; i < VIRTUAL_NODES_PER_NODE; i++) {
            ring.put(hash(node.toString() + "#vnode" + i), node);
        }
    }

    @Override
    public void removeNode(@NonNull NodeId node) {
        ring.entrySet().removeIf(e -> e.getValue().equals(node));
    }

    @Override
    public NodeId nodeFor(@NonNull Object key) {
        long h = hash(String.valueOf(key));
        Map.Entry<Long, NodeId> entry = ring.ceilingEntry(h);
        if (entry == null) {
            entry = ring.firstEntry(); // wrap around the ring
        }
        return entry.getValue();
    }

    @Override
    public List<NodeId> nodesFor(@NonNull Object key, int replicationFactor) {
        long h = hash(String.valueOf(key));
        NavigableMap<Long, NodeId> tail = ring.tailMap(h, true);

        List<NodeId> result = new ArrayList<>(replicationFactor);
        for (NodeId candidate : tail.values()) {
            addIfAbsent(result, candidate, replicationFactor);
            if (result.size() == replicationFactor) return result;
        }
        for (NodeId candidate : ring.values()) { // wrap around once the end of the ring is reached
            addIfAbsent(result, candidate, replicationFactor);
            if (result.size() == replicationFactor) break;
        }
        return result;
    }

    private void addIfAbsent(List<NodeId> result, NodeId candidate, int max) {
        if (!result.contains(candidate) && result.size() < max) {
            result.add(candidate);
        }
    }

    private long hash(String input) {
        byte[] bytes = DIGEST.get().digest(input.getBytes(StandardCharsets.UTF_8));
        // interpret the first 8 bytes of the digest as a long -> uniformly distributed hash
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (bytes[i] & 0xff);
        }
        return result;
    }
}
