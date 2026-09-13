"""Mirror of ``de.lino.database.utils.cache.Cache``."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Generic, TypeVar

ID = TypeVar("ID")
T = TypeVar("T")


class Cache(ABC, Generic[ID, T]):
    """Generic, concurrency-safe, asynchronous cache for entities of type ``T``, keyed by
    an identifier of type ``ID``.

    Complexity expected of a well-behaved implementation: :meth:`get`, :meth:`put` and
    :meth:`invalidate` are O(1) amortized; :meth:`evict_expired` is a full O(n) scan and
    should be called periodically (e.g. every 60s via a scheduler) rather than on the hot
    path.

    Design intent:

    - **Stampede protection:** concurrent requests for the same, not-yet-cached key
      should trigger the loader only once; all callers share the same pending result.
    - **Async:** :meth:`get` is a coroutine and must never block the event loop; the
      loader supplies an awaitable the implementation stores and callers share.
    - **Single implementation module:** this contract lives in the API package; concrete
      implementations (e.g. an in-memory, TTL/size-bounded cache) live in the plugin
      package so consumers only ever depend on this contract.
    """

    @abstractmethod
    async def get(self, id: ID) -> T:
        """Returns the entity for the given key - from the cache if present and not
        expired, otherwise loaded via the configured loader. Must never block the event
        loop.

        Raises:
            Exception: Whatever the loader raised, if loading fails.
        """

    @abstractmethod
    def put(self, id: ID, value: T) -> None:
        """Writes a value directly into the cache, e.g. after a successful save,
        bypassing the loader."""

    @abstractmethod
    def invalidate(self, id: ID) -> None:
        """Removes a single entry from the cache, if present."""

    @abstractmethod
    def invalidate_all(self) -> None:
        """Removes all entries from the cache."""

    @abstractmethod
    def evict_expired(self) -> None:
        """Removes all expired entries. This is a full scan and should be invoked
        periodically rather than on every request."""

    @abstractmethod
    def size(self) -> int:
        """Returns the current number of entries held by this cache, including entries
        that are in the process of loading."""

    @abstractmethod
    def snapshot(self) -> dict[ID, T]:
        """Takes a snapshot of the currently loaded, non-expired entries. Intended for
        debugging and metrics, not for hot-path use."""
