"""Mirror of ``de.lino.database.utils.cache.provider.Caches``."""

from __future__ import annotations

import threading
from collections.abc import Awaitable, Callable
from datetime import timedelta
from importlib.metadata import entry_points
from typing import Any

from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.cache.clustered_cache import ClusteredCache
from database_driver.api.utils.cache.provider.cache_provider import CacheProvider

ENTRY_POINT_GROUP = "database_driver.cache_provider"
"""The entry-point group a provider distribution registers its ``CacheProvider``
implementation under - the analogue of the Java edition's
``META-INF/services/de.lino.database.utils.cache.provider.CacheProvider`` resource."""

_provider: CacheProvider | None = None
_lock = threading.Lock()


def new_cache(
    loader: Callable[[Any], Awaitable[Any]],
    ttl: timedelta | None = None,
    max_size: int = -1,
) -> Cache[Any, Any]:
    """Creates a new ``Cache`` without the caller depending on any concrete
    implementation class; ``max_size`` defaults to unbounded, folding the Java edition's
    two overloads into one signature.

    Args:
        loader: Asynchronously supplies an entity when it is not (or no longer) cached.
        ttl: Validity duration of an entry, ``None`` for unbounded.
        max_size: Maximum number of entries; ``<= 0`` for unbounded.
    """
    return _resolve_provider().new_cache(loader, ttl, max_size)


def new_clustered_cache(
    shard_count: int,
    replication_factor: int,
    loader: Callable[[Any], Awaitable[Any]],
    ttl: timedelta | None,
    max_size_per_shard: int,
) -> ClusteredCache[Any, Any]:
    """Creates a new ``ClusteredCache`` without the caller depending on any concrete
    implementation class.

    Args:
        shard_count: Number of shards to partition the key space into.
        replication_factor: Number of shards each key is simultaneously stored on.
        loader: Supplies an entity if it is not cached on any shard.
        ttl: Validity duration per entry.
        max_size_per_shard: Size limit per shard.
    """
    return _resolve_provider().new_clustered_cache(shard_count, replication_factor, loader, ttl, max_size_per_shard)


def _resolve_provider() -> CacheProvider:
    """Discovers the ``CacheProvider`` implementation through the
    :data:`ENTRY_POINT_GROUP` entry-point group, caching it after the first lookup so
    repeated calls do not re-scan the installed distributions - the double-checked
    locking mirror of the Java edition's ``ServiceLoader`` resolution.

    Raises:
        RuntimeError: If no implementation is installed.
    """
    global _provider

    result = _provider
    if result is None:
        with _lock:
            result = _provider
            if result is None:
                discovered = list(entry_points(group=ENTRY_POINT_GROUP))
                if not discovered:
                    raise RuntimeError(
                        "No CacheProvider implementation found. Install a distribution providing a "
                        f"'{ENTRY_POINT_GROUP}' entry point (e.g. lino-database-driver-plugin) as a dependency."
                    )
                provider_type = discovered[0].load()
                result = provider_type()
                _provider = result

    return result
