"""Mirror of ``de.lino.database.utility.cache.DefaultConsistentHashRing``."""

from __future__ import annotations

import bisect
import hashlib
import threading
from collections.abc import Sequence
from typing import Any, TypeVar

from database_driver.api.utils.cache.consistent_hash_ring import ConsistentHashRing

NodeId = TypeVar("NodeId")

_VIRTUAL_NODES_PER_NODE = 100


class DefaultConsistentHashRing(ConsistentHashRing[NodeId]):
    """Default implementation of ``ConsistentHashRing``, in pure Python (only
    :mod:`hashlib` for SHA-256, part of the stdlib).

    Each physical node is assigned several "virtual nodes" on the ring, which spreads
    load more evenly and avoids hotspots. A key is hashed onto the ring and routed to the
    next node clockwise.

    Complexity: :meth:`nodes_for` is O(log V) for the ring lookup (V = total number of
    virtual nodes) plus O(R) for replica selection - independent of the number of stored
    keys n. Whether there are 10^3 or 10^12 keys, routing itself stays equally fast.

    Thread-safety: the ring is a sorted list guarded by one lock - the Python stand-in
    for the Java edition's ``ConcurrentSkipListMap`` - so :meth:`add_node` /
    :meth:`remove_node` can safely run concurrently with lookups (e.g. while rebalancing
    a live cluster). SHA-256 needs no per-thread digest caching here: :func:`hashlib.sha256`
    construction is cheap in CPython, unlike the JVM's ``MessageDigest`` lookup.
    """

    def __init__(self, nodes: Sequence[NodeId]) -> None:
        """Args:
            nodes: Initial set of nodes to place on the ring; must not be empty.

        Raises:
            ValueError: If ``nodes`` is empty.
        """
        if not nodes:
            raise ValueError("at least one node is required")
        self._lock = threading.Lock()
        self._hashes: list[int] = []
        self._nodes: list[NodeId] = []
        for node in nodes:
            self.add_node(node)

    def add_node(self, node: NodeId) -> None:
        if node is None:
            raise TypeError("node must not be None")
        with self._lock:
            for i in range(_VIRTUAL_NODES_PER_NODE):
                position = _hash(f"{node}#vnode{i}")
                index = bisect.bisect_left(self._hashes, position)
                # A hash collision overwrites the colliding slot, matching the Java
                # NavigableMap's put-on-same-key semantics.
                if index < len(self._hashes) and self._hashes[index] == position:
                    self._nodes[index] = node
                else:
                    self._hashes.insert(index, position)
                    self._nodes.insert(index, node)

    def remove_node(self, node: NodeId) -> None:
        if node is None:
            raise TypeError("node must not be None")
        with self._lock:
            keep = [(position, owner) for position, owner in zip(self._hashes, self._nodes) if owner != node]
            self._hashes = [position for position, _ in keep]
            self._nodes = [owner for _, owner in keep]

    def node_for(self, key: Any) -> NodeId:
        if key is None:
            raise TypeError("key must not be None")
        position = _hash(str(key))
        with self._lock:
            index = bisect.bisect_left(self._hashes, position)
            if index == len(self._hashes):
                index = 0  # wrap around the ring
            return self._nodes[index]

    def nodes_for(self, key: Any, replication_factor: int) -> list[NodeId]:
        if key is None:
            raise TypeError("key must not be None")
        position = _hash(str(key))
        result: list[NodeId] = []
        with self._lock:
            start = bisect.bisect_left(self._hashes, position)
            total = len(self._nodes)
            for step in range(total):  # walk clockwise, wrapping once around
                candidate = self._nodes[(start + step) % total]
                if candidate not in result and len(result) < replication_factor:
                    result.append(candidate)
                if len(result) == replication_factor:
                    break
        return result


def _hash(value: str) -> int:
    """Interprets the first 8 bytes of the value's SHA-256 digest as an unsigned integer
    - a uniformly distributed ring position, mirroring the Java edition's digest-to-long
    fold."""
    return int.from_bytes(hashlib.sha256(value.encode("utf-8")).digest()[:8], "big")
