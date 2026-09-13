"""Mirror of ``de.lino.database.utils.cache.ClusteredCache``."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Generic, TypeVar

ID = TypeVar("ID")
T = TypeVar("T")


class ClusteredCache(ABC, Generic[ID, T]):
    """Partitions entities across multiple ``Cache`` shards following the Cassandra
    principle (consistent hashing + replication).

    Conceptually, each "node" is a separate ``Cache`` instance that owns part of the key
    space. A ``ConsistentHashRing`` decides which shards are responsible for a given key,
    including replication across multiple shards.

    **Honest note on scale:** an implementation of this contract may only simulate
    Cassandra's partitioning principle within a single process. True distribution across
    multiple physical machines additionally requires network communication between
    processes, with each shard running as its own process on its own hardware - the
    routing principle stays identical, only the access to a given node changes from a
    direct method call to a network call.
    """

    @abstractmethod
    async def get(self, id: ID) -> T:
        """Reads an entity. Queries the primary shard (first replica node per the ring)
        for the given key."""

    @abstractmethod
    async def put(self, id: ID, value: T) -> None:
        """Writes a value to all replica shards responsible for this key (analogous to
        Cassandra's replication factor). Expected to run the per-shard writes
        concurrently, not sequentially."""

    @abstractmethod
    def invalidate(self, id: ID) -> None:
        """Removes a key from every replica shard responsible for it."""

    @abstractmethod
    def total_size(self) -> int:
        """Returns the total number of cached entries across all shards; replicated
        entries are counted once per replica."""

    @abstractmethod
    def shard_count(self) -> int:
        """Returns the number of shards this cache is partitioned into."""
