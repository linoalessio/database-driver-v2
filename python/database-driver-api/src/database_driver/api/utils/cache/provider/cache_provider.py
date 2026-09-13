"""Mirror of ``de.lino.database.utils.cache.provider.CacheProvider``."""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Awaitable, Callable
from datetime import timedelta
from typing import Any

from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.cache.clustered_cache import ClusteredCache


class CacheProvider(ABC):
    """Service-provider interface that supplies concrete ``Cache`` and ``ClusteredCache``
    instances for ``Caches``.

    The API package only declares the ``Cache`` / ``ClusteredCache`` contracts and cannot
    depend on their implementations, which live in a separate distribution (e.g.
    ``database-driver-plugin``). Implementations are discovered at runtime through the
    ``database_driver.cache_provider`` entry-point group - the Python packaging analogue
    of Java's ``ServiceLoader`` and its ``META-INF/services`` resource file: a provider
    distribution registers its implementation class under that group in its own
    packaging metadata.
    """

    @abstractmethod
    def new_cache(
        self,
        loader: Callable[[Any], Awaitable[Any]],
        ttl: timedelta | None,
        max_size: int,
    ) -> Cache[Any, Any]:
        """Creates a new ``Cache`` instance.

        Args:
            loader: Asynchronously supplies an entity when it is not (or no longer)
                cached.
            ttl: Validity duration of an entry, ``None`` for unbounded.
            max_size: Maximum number of entries; ``<= 0`` for unbounded.
        """

    @abstractmethod
    def new_clustered_cache(
        self,
        shard_count: int,
        replication_factor: int,
        loader: Callable[[Any], Awaitable[Any]],
        ttl: timedelta | None,
        max_size_per_shard: int,
    ) -> ClusteredCache[Any, Any]:
        """Creates a new ``ClusteredCache`` instance.

        Args:
            shard_count: Number of shards to partition the key space into.
            replication_factor: Number of shards each key is simultaneously stored on.
            loader: Supplies an entity if it is not cached on any shard.
            ttl: Validity duration per entry.
            max_size_per_shard: Size limit per shard.
        """
