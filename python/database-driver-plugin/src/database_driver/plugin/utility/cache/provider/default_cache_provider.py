"""Mirror of ``de.lino.database.utility.cache.provider.DefaultCacheProvider``."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from datetime import timedelta
from typing import Any

from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.cache.clustered_cache import ClusteredCache
from database_driver.api.utils.cache.provider.cache_provider import CacheProvider

from database_driver.plugin.utility.cache.default_cache import DefaultCache
from database_driver.plugin.utility.cache.default_clustered_cache import DefaultClusteredCache


class DefaultCacheProvider(CacheProvider):
    """Default ``CacheProvider``, backing the api package's ``Caches`` with
    ``DefaultCache`` and ``DefaultClusteredCache``. Registered under the
    ``database_driver.cache_provider`` entry-point group in this distribution's
    packaging metadata - the Python analogue of the Java module's
    ``META-INF/services`` resource - so it is picked up automatically once this
    distribution is installed."""

    def new_cache(
        self,
        loader: Callable[[Any], Awaitable[Any]],
        ttl: timedelta | None,
        max_size: int,
    ) -> Cache[Any, Any]:
        return DefaultCache(loader, ttl, max_size)

    def new_clustered_cache(
        self,
        shard_count: int,
        replication_factor: int,
        loader: Callable[[Any], Awaitable[Any]],
        ttl: timedelta | None,
        max_size_per_shard: int,
    ) -> ClusteredCache[Any, Any]:
        return DefaultClusteredCache(shard_count, replication_factor, loader, ttl, max_size_per_shard)
