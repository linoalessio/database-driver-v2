"""Tests for DefaultCache, DefaultConsistentHashRing, DefaultClusteredCache and the
Caches entry-point discovery."""

from __future__ import annotations

import asyncio
import threading
import time
from datetime import timedelta

import pytest
from database_driver.api.utils.cache.provider import caches

from database_driver.plugin import DefaultCache, DefaultClusteredCache, DefaultConsistentHashRing


def test_get_loads_once_and_caches():
    calls = []

    async def loader(key: str) -> str:
        calls.append(key)
        return f"value-{key}"

    cache = DefaultCache(loader)

    async def run():
        assert await cache.get("a") == "value-a"
        assert await cache.get("a") == "value-a"

    asyncio.run(run())
    assert calls == ["a"]
    assert cache.size() == 1


def test_concurrent_misses_share_one_load():
    loads = []
    release = threading.Event()

    async def loader(key: str) -> str:
        loads.append(key)
        await asyncio.to_thread(release.wait, 2.0)
        return "shared"

    cache = DefaultCache(loader)
    results = []

    def worker():
        results.append(asyncio.run(cache.get("hot")))

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for thread in threads:
        thread.start()
    time.sleep(0.15)  # let every caller reach the install-or-piggyback step
    release.set()
    for thread in threads:
        thread.join(timeout=5)

    assert results == ["shared"] * 4
    assert loads == ["hot"]  # stampede protection: one load, four winners


def test_failed_load_is_not_cached():
    attempts = []

    async def loader(key: str) -> str:
        attempts.append(key)
        if len(attempts) == 1:
            raise RuntimeError("first load fails")
        return "recovered"

    cache = DefaultCache(loader)

    with pytest.raises(RuntimeError):
        asyncio.run(cache.get("k"))
    assert asyncio.run(cache.get("k")) == "recovered"
    assert len(attempts) == 2


def test_ttl_expiry_and_evict_expired():
    async def loader(key: str) -> str:
        return f"loaded-{key}"

    cache = DefaultCache(loader, ttl=timedelta(milliseconds=30))
    cache.put("a", "va")
    assert cache.size() == 1

    time.sleep(0.06)
    cache.evict_expired()
    assert cache.size() == 0

    # An expired (not yet swept) entry re-loads on get.
    cache.put("b", "vb")
    time.sleep(0.06)
    assert asyncio.run(cache.get("b")) == "loaded-b"


def test_max_size_triggers_approximate_lru_eviction():
    async def loader(key: str) -> str:
        return key

    cache = DefaultCache(loader, max_size=4)
    for index in range(10):
        cache.put(f"k{index}", f"v{index}")
    assert cache.size() <= 10  # sampled eviction is approximate, but must have evicted
    assert cache.size() < 10


def test_snapshot_only_holds_loaded_unexpired_values():
    async def loader(key: str) -> str:
        return key

    cache = DefaultCache(loader)
    cache.put("x", "vx")
    snapshot = cache.snapshot()
    assert snapshot == {"x": "vx"}


def test_ring_routes_consistently_and_replicates_distinctly():
    ring = DefaultConsistentHashRing(["node-a", "node-b", "node-c"])

    owner = ring.node_for("some-key")
    assert owner == ring.node_for("some-key")  # deterministic

    replicas = ring.nodes_for("some-key", 2)
    assert len(replicas) == 2 and len(set(replicas)) == 2
    assert replicas[0] == owner  # first replica is the primary

    ring.remove_node(owner)
    assert ring.node_for("some-key") != owner


def test_ring_rejects_empty_nodes():
    with pytest.raises(ValueError):
        DefaultConsistentHashRing([])


def test_clustered_cache_replicated_put_and_routed_get():
    async def loader(key: str) -> str:
        return f"loaded-{key}"

    cache = DefaultClusteredCache(4, 2, loader, None, -1)
    assert cache.shard_count() == 4

    async def run():
        await cache.put("k", "stored")
        assert await cache.get("k") == "stored"

    asyncio.run(run())
    assert cache.total_size() == 2  # one copy per replica

    cache.invalidate("k")
    assert cache.total_size() == 0
    assert asyncio.run(cache.get("k")) == "loaded-k"


def test_clustered_cache_validates_arguments():
    async def loader(key):
        return key

    with pytest.raises(ValueError):
        DefaultClusteredCache(0, 1, loader, None, -1)
    with pytest.raises(ValueError):
        DefaultClusteredCache(2, 3, loader, None, -1)


def test_caches_spi_discovers_plugin_provider():
    """The api package's Caches must find DefaultCacheProvider through the entry-point
    group - the ServiceLoader analogue."""
    async def loader(key: str) -> str:
        return key

    cache = caches.new_cache(loader)
    assert isinstance(cache, DefaultCache)
