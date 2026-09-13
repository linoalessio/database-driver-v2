"""Mirror of ``de.lino.database.utility.cache.DefaultCache``."""

from __future__ import annotations

import asyncio
import concurrent.futures
import threading
import time
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Any, Generic, TypeVar

from database_driver.api.utils.cache.cache import Cache

ID = TypeVar("ID")
T = TypeVar("T")

_EVICTION_SAMPLE_SIZE = 8


class _Entry(Generic[T]):
    """One cached slot: a thread-safe ``concurrent.futures.Future`` (shared by every
    caller that piggybacked on the same load - the stampede protection), plus the TTL
    and approximate-LRU bookkeeping the eviction paths read."""

    __slots__ = ("expires_at", "future", "last_access_nanos")

    def __init__(self, future: concurrent.futures.Future, expires_at: datetime | None) -> None:
        self.future = future
        self.expires_at = expires_at
        self.last_access_nanos = time.monotonic_ns()

    def is_expired(self) -> bool:
        return self.expires_at is not None and datetime.now(UTC) > self.expires_at

    def is_failed_or_expired(self) -> bool:
        if not self.future.done():
            return False  # still loading - piggyback instead of restarting
        if self.future.exception() is not None:
            return True
        return self.is_expired()

    def touch(self) -> None:
        self.last_access_nanos = time.monotonic_ns()


class DefaultCache(Cache[ID, T]):
    """Default, in-memory implementation of ``Cache``, backed by a lock-guarded dict of
    shared futures - the Python counterpart of the Java edition's
    ``ConcurrentHashMap<ID, CompletableFuture<Entry>>``.

    Complexity: :meth:`get`/:meth:`put`/:meth:`invalidate` are O(1) amortized;
    :meth:`evict_expired` is an O(n) full scan meant for a periodic sweeper, not the hot
    path. Size-based eviction (when ``max_size`` is set) is O(k) with a constant sample
    factor (default 8), independent of n - Redis' "approximated LRU": instead of locating
    the exact oldest entry, a small sample is compared and the oldest candidate within it
    is evicted, keeping the insert path constant-time even with very many entries.

    Design decisions carried over from the Java edition:

    - **Stampede protection:** the install-or-reuse step runs under one lock (the
      analogue of ``ConcurrentHashMap#compute``), so under concurrent requests for the
      same key the loader runs only ONCE - all callers share the same future. The loader
      coroutine itself runs *outside* the lock, exactly as the Java loader runs outside
      the map's bin lock.
    - **Async:** :meth:`get` never blocks the event loop; awaiting a
      ``concurrent.futures.Future`` through ``asyncio.wrap_future`` suspends rather than
      blocks, and works from any event loop - including the private per-call loops the
      section engine's synchronous read path spins up.
    - **Weak-referenceable:** instances deliberately carry no ``__slots__``, since the
      registry's TTL sweeper holds them in a weak-keyed map.
    """

    def __init__(
        self,
        loader: Callable[[ID], Awaitable[T]],
        ttl: timedelta | None = None,
        max_size: int = -1,
    ) -> None:
        """Args:
            loader: Asynchronously supplies an entity when it is not (or no longer)
                cached; must not resolve to ``None``.
            ttl: Validity duration of an entry, ``None`` for unbounded.
            max_size: Maximum number of entries; ``<= 0`` for unbounded. Approximate-LRU
                eviction (O(k)) is triggered once this is exceeded.
        """
        if loader is None:
            raise TypeError("loader must not be None")
        self._loader = loader
        self._ttl = ttl
        self._max_size = max_size
        self._store: dict[ID, _Entry[T]] = {}
        self._lock = threading.Lock()

    async def get(self, id: ID) -> T:
        if id is None:
            raise TypeError("id must not be None")

        load_owner = False
        with self._lock:
            entry = self._store.get(id)
            if entry is None or entry.is_failed_or_expired():
                entry = _Entry(concurrent.futures.Future(), self._expiry())
                self._store[id] = entry
                load_owner = True

        if load_owner:
            try:
                value = await self._loader(id)
                if value is None:
                    raise TypeError("loader must not return None values")
                entry.future.set_result(value)
            except BaseException as failure:
                entry.future.set_exception(failure)

        self._maybe_evict_over_capacity()

        result: T = await asyncio.wrap_future(entry.future)
        entry.touch()  # update access time for approximate LRU
        return result

    def put(self, id: ID, value: T) -> None:
        if id is None:
            raise TypeError("id must not be None")
        if value is None:
            raise TypeError("value must not be None")
        future: concurrent.futures.Future = concurrent.futures.Future()
        future.set_result(value)
        with self._lock:
            self._store[id] = _Entry(future, self._expiry())
        self._maybe_evict_over_capacity()

    def invalidate(self, id: ID) -> None:
        if id is None:
            raise TypeError("id must not be None")
        with self._lock:
            self._store.pop(id, None)

    def invalidate_all(self) -> None:
        with self._lock:
            self._store.clear()

    def evict_expired(self) -> None:
        with self._lock:
            expired = [id for id, entry in self._store.items() if entry.is_failed_or_expired()]
            for id in expired:
                del self._store[id]

    def size(self) -> int:
        return len(self._store)

    def snapshot(self) -> dict[ID, T]:
        result: dict[ID, T] = {}
        with self._lock:
            entries = list(self._store.items())
        for id, entry in entries:
            if entry.future.done() and entry.future.exception() is None and not entry.is_expired():
                result[id] = entry.future.result()
        return result

    def _expiry(self) -> datetime | None:
        return None if self._ttl is None else datetime.now(UTC) + self._ttl

    def _maybe_evict_over_capacity(self) -> None:
        """Approximate-LRU eviction once ``max_size`` is exceeded: draws a constant
        sample instead of scanning the whole cache, and evicts the least-recently-used
        entry within it. O(k), independent of the cache size n."""
        if self._max_size <= 0 or len(self._store) <= self._max_size:
            return

        oldest_key: Any = None
        oldest_access = None

        with self._lock:
            for sampled, (id, entry) in enumerate(self._store.items()):
                if sampled >= _EVICTION_SAMPLE_SIZE:
                    break
                if entry.future.done() and entry.future.exception() is None:
                    if oldest_access is None or entry.last_access_nanos < oldest_access:
                        oldest_access = entry.last_access_nanos
                        oldest_key = id
            if oldest_key is not None:
                self._store.pop(oldest_key, None)
