"""Mirror of ``de.lino.database.utility.cache.DefaultClusteredCache``."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from datetime import timedelta
from typing import TypeVar

from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.cache.clustered_cache import ClusteredCache
from database_driver.plugin.utility.cache.default_cache import DefaultCache
from database_driver.plugin.utility.cache.default_consistent_hash_ring import DefaultConsistentHashRing

ID = TypeVar("ID")
T = TypeVar("T")


class DefaultClusteredCache(ClusteredCache[ID, T]):
    """Default implementation of ``ClusteredCache``, partitioning entities across
    multiple ``DefaultCache`` shards using ``DefaultConsistentHashRing`` for routing and
    replication, in pure Python with no dependencies beyond the stdlib.

    **Honest note on scale:** this class simulates Cassandra's partitioning principle
    WITHIN a single process. For real distribution across multiple physical machines,
    network communication between processes would additionally be required - each shard
    would then run as its own process on its own hardware. The routing principle (this
    ring) stays identical; only access to a given node would go over the network instead
    of a direct method call.
    """

    def __init__(
        self,
        shard_count: int,
        replication_factor: int,
        loader: Callable[[ID], Awaitable[T]],
        ttl: timedelta | None,
        max_size_per_shard: int,
    ) -> None:
        """Args:
            shard_count: Number of simulated nodes (e.g. CPU core count, or - in true
                distribution - machine count).
            replication_factor: Number of shards each key is simultaneously stored on.
            loader: Supplies an entity if it is not cached on any shard.
            ttl: Validity duration per entry.
            max_size_per_shard: Size limit PER shard (total capacity =
                shard_count * max_size_per_shard).
        """
        if shard_count < 1:
            raise ValueError("shard_count must be >= 1")
        if replication_factor < 1 or replication_factor > shard_count:
            raise ValueError("replication_factor must be between 1 and shard_count")
        if loader is None:
            raise TypeError("loader must not be None")

        self._replication_factor = replication_factor
        self._shards: dict[int, Cache[ID, T]] = {
            index: DefaultCache(loader, ttl, max_size_per_shard) for index in range(shard_count)
        }
        self._ring = DefaultConsistentHashRing(list(range(shard_count)))

    async def get(self, id: ID) -> T:
        if id is None:
            raise TypeError("id must not be None")
        primary_shard = self._ring.node_for(id)
        return await self._shards[primary_shard].get(id)

    async def put(self, id: ID, value: T) -> None:
        """Writes to all replica shards responsible for this key concurrently, not
        sequentially - the analogue of the Java edition fanning the per-shard writes out
        on the common pool."""
        if id is None:
            raise TypeError("id must not be None")
        if value is None:
            raise TypeError("value must not be None")

        target_shards = self._ring.nodes_for(id, self._replication_factor)
        await asyncio.gather(
            *(asyncio.to_thread(self._shards[shard_id].put, id, value) for shard_id in target_shards)
        )

    def invalidate(self, id: ID) -> None:
        if id is None:
            raise TypeError("id must not be None")
        for shard_id in self._ring.nodes_for(id, self._replication_factor):
            self._shards[shard_id].invalidate(id)

    def total_size(self) -> int:
        return sum(shard.size() for shard in self._shards.values())

    def shard_count(self) -> int:
        return len(self._shards)
