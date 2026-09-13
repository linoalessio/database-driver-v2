"""Mirror of ``de.lino.database.database.AbstractCachedDatabaseSection``."""

from __future__ import annotations

import asyncio
import heapq
import threading
import time
from abc import abstractmethod
from collections.abc import Callable
from dataclasses import dataclass

from database_driver.api.database.cache_mode import CacheMode
from database_driver.api.database.database_section import DatabaseSection
from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.data_already_exist import DataAlreadyExist
from database_driver.api.database.exception.no_such_entry_found import NoSuchEntryFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.cache.provider import caches


@dataclass(frozen=True, slots=True)
class SectionStats:
    """A read-only snapshot of a section's cache activity since construction - the
    numbers that tell whether the configured ``CacheMode`` actually fits the workload: a
    ``BOUNDED`` section with a poor :meth:`cache_hit_ratio` wants a larger bound or
    ``FULL``; a ``FULL`` section whose ``full_loads``/``full_load_nanos`` dominate wants
    ``LAZY`` or less.

    The hit/miss split covers ``find_entry_by_id`` only (aggregate reads and writes have
    no meaningful hit notion), and under concurrent access it is approximate: concurrent
    misses for one id share a single backend load by design, so they count one miss
    however many callers piggybacked on it.

    Attributes:
        cache_hits: Point lookups answered without reading the backing store.
        cache_misses: Point lookups that read the backing store.
        full_loads: Whole-section ``load_all`` passes (warm-ups, reloads, whole-section
            enumerations in the non-materialized modes).
        full_load_nanos: Cumulative wall-clock nanoseconds spent in those passes.
    """

    cache_hits: int
    cache_misses: int
    full_loads: int
    full_load_nanos: int

    def cache_hit_ratio(self) -> float:
        """The fraction of point lookups served without touching the backing store, or
        ``1.0`` for a section never point-read - "no lookup was ever slow" is the honest
        degenerate answer, and it keeps dashboards from flagging idle sections."""
        lookups = self.cache_hits + self.cache_misses
        return 1.0 if lookups == 0 else self.cache_hits / lookups


class _MaxHeapById:
    """Wraps a ``DatabaseEntry`` so :mod:`heapq`'s min-heap behaves as a max-heap on
    entry id - the bounded window :meth:`AbstractCachedDatabaseSection.page_remote`
    streams into."""

    __slots__ = ("entry",)

    def __init__(self, entry: DatabaseEntry) -> None:
        self.entry = entry

    def __lt__(self, other: _MaxHeapById) -> bool:
        return self.entry.id > other.entry.id


class AbstractCachedDatabaseSection(DatabaseSection):
    """The single shared caching engine behind every ``DatabaseSection`` implementation
    shipped by this module. Historically each backend section carried its own copy of the
    identical pattern - an in-memory ``dict`` filled once at construction, consulted by
    every read and kept in sync by every write - which meant drifting copies of the same
    cache logic and a memory floor that grew with the database forever. This class owns
    that logic exactly once: a backend section only supplies its *storage primitives*
    (the eight abstract methods below) and inherits every ``DatabaseSection`` method from
    here, behaving according to the ``SectionConfig`` it was constructed with:

    - ``FULL`` / ``LAZY`` - a materialized in-memory view of the whole section, backed by
      a plain dict. ``FULL`` is warmed by the owning provider's ``create_section`` right
      after construction (the historical timing); ``LAZY`` defers the same one-time load
      to the first data access. A plain dict rather than an unbounded ``Cache`` is
      deliberate: these modes' hot path is enumeration of a materialized table view, and
      the cache's per-entry wrappers would add real per-row overhead for no benefit.
    - ``BOUNDED`` - a read-through, size-bounded (optionally expiring) ``Cache`` obtained
      through the ``Caches`` SPI, with :meth:`fetch_one` as the loader. Concurrent misses
      for one id collapse into a single backend read (the cache's stampede protection),
      writes go through to the store and then refresh the cache, so the owning process
      always reads its own writes.
    - ``NONE`` - no memory at all; every call is answered by a primitive.

    Aggregate reads in ``BOUNDED``/``NONE`` (``count``, ``exists``, ``get_entries``)
    always go to the backing store - a partial cache can prove presence but never
    absence.

    **Concurrency contract:** best-effort, matching the historical sections - no new
    guarantees. The map-backed modes claim inserted ids atomically (``setdefault``), but
    a ``reload`` racing a reader may briefly expose a partially repopulated view, exactly
    as before; the remote-checked modes' insert precondition (a not-exists check followed
    by the write) is best-effort by nature.
    """

    def __init__(self, name: str, config: SectionConfig) -> None:
        """Prepares the engine's per-mode backing state. Deliberately loads *nothing*, in
        every mode: the concrete section's constructor must first initialize the storage
        handles the primitives need, and section construction itself must stay cheap so
        providers can materialize sections on demand without touching row data - the
        ``FULL`` warm-up is triggered by the owning provider through :meth:`warm_up`."""
        self._name = name
        self._config = config

        self._entries: dict[str, DatabaseEntry] | None = None
        self._cache: Cache[str, DatabaseEntry] | None = None

        self._loaded = False
        self._load_lock = threading.Lock()

        self._counter_lock = threading.Lock()
        self._point_lookups = 0
        self._point_loads = 0
        self._full_loads = 0
        self._full_load_nanos = 0

        if config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            self._entries = {}
        elif config.cache_mode is CacheMode.BOUNDED:
            self._cache = caches.new_cache(self._load_entry, config.ttl, config.max_entries)
            if config.ttl is not None:
                # Local import: the registry imports this module's providers transitively.
                from database_driver.plugin.database_repository_registry import DatabaseRepositoryRegistry

                DatabaseRepositoryRegistry.schedule_ttl_sweeps(self._cache)

    # ------------------------------------------------------------- primitives

    @abstractmethod
    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Streams every row of the backing store to ``consumer``, without ever
        materializing the whole table in the implementation itself (SQL backends page
        through the result set with a bounded fetch size, cursor-based backends iterate
        their cursor, file backends walk their directory or file line by line). The
        engine - not the backend - decides what to do with the streamed entries."""

    @abstractmethod
    def fetch_one(self, id: str) -> DatabaseEntry | None:
        """Reads the single row stored under ``id`` directly from the backing store,
        bypassing any in-memory state. This is the point read behind ``NONE``'s
        ``find_entry_by_id`` and the loader behind ``BOUNDED``'s cache; backends without
        an efficient point lookup implement it as a bounded scan and document that cost
        on their override."""

    @abstractmethod
    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        """Persists ``database_entry`` as a new row of the backing store. Storage only:
        the duplicate check and the cache bookkeeping are the engine's job (see
        :meth:`insert`), so an implementation must not consult or modify any cache state
        here."""

    @abstractmethod
    def persist_update(self, database_entry: DatabaseEntry) -> None:
        """Replaces the backing store's row under ``database_entry``'s id. Storage only:
        the presence check and the cache bookkeeping are the engine's job."""

    @abstractmethod
    def persist_delete(self, id: str) -> None:
        """Removes the backing store's row under ``id``. Storage only: the presence check
        and the cache bookkeeping are the engine's job."""

    @abstractmethod
    def count_remote(self) -> int:
        """Counts the rows of the backing store natively (SQL ``COUNT(*)``, Mongo
        ``count_documents``, a file count ...), bypassing any in-memory state - how the
        non-materialized modes answer :meth:`count`, since a partial cache cannot count
        what it never held."""

    @abstractmethod
    def exists_remote(self, id: str) -> bool:
        """Checks natively whether the backing store holds a row under ``id``, bypassing
        any in-memory state - how the non-materialized modes answer :meth:`exists` and
        guard their writes, since a partial cache can prove presence but never absence."""

    @abstractmethod
    def clear_remote(self) -> None:
        """Removes every row of the backing store (SQL ``TRUNCATE``/``DELETE``, Mongo
        ``delete_many``, a file wipe ...). Storage only: the engine resets its own cache
        state afterwards (see :meth:`clear`)."""

    # ------------------------------------------------------------ engine hooks

    def get_config(self) -> SectionConfig:
        """How this section holds entries in memory - the configuration it was
        constructed with, exposed so the owning provider can decide whether a re-declared
        section may keep this instance or must be replaced."""
        return self._config

    def warm_up(self) -> None:
        """Performs the one-time synchronous load for a ``FULL`` section; a no-op in
        every other mode (and once already warm). Providers call this right after
        constructing a ``FULL`` section from ``create_section``, which is what preserves
        the historical "warm by the time ``create_section`` returns" timing while keeping
        construction itself free of row I/O - a ``FULL`` section merely *materialized*
        another way (e.g. via ``get_section``) stays cold until its first data access."""
        if self._config.cache_mode is CacheMode.FULL:
            self._ensure_loaded()

    def cached_entry(self, id: str) -> DatabaseEntry | None:
        """The engine's current in-memory view of ``id``, for the rare storage primitive
        whose on-disk format needs the *previous* row state to build the next one (see
        the JSON store's merge-style ``persist_update``). In the map-backed modes this
        preserves the historical behavior exactly; in every other mode it is empty by
        design (a bounded cache peek would have to load, which a storage primitive must
        never trigger), so callers fall back to their own storage read."""
        return None if self._entries is None else self._entries.get(id)

    # ------------------------------------------------------- DatabaseSection

    def get_name(self) -> str:
        return self._name

    def reload(self) -> None:
        """Per mode: ``FULL`` discards the in-memory view and synchronously re-populates
        it from the backing store (the historical behavior); ``LAZY`` just discards it
        and lets the next data access pay the re-load; ``BOUNDED`` invalidates the cache
        so every entry is re-read on next access; ``NONE`` has nothing to discard and is
        a no-op - its reads never left the backing store in the first place."""
        mode = self._config.cache_mode
        if mode is CacheMode.FULL:
            entries = self._entries
            assert entries is not None
            with self._load_lock:
                entries.clear()
                self._stream_all(lambda entry: entries.__setitem__(entry.id, entry))
                self._loaded = True
        elif mode is CacheMode.LAZY:
            assert self._entries is not None
            with self._load_lock:
                self._entries.clear()
                self._loaded = False
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            self._cache.invalidate_all()

    def insert(self, database_entry: DatabaseEntry) -> None:
        """In the map-backed modes the id is claimed in memory atomically (``setdefault``)
        *before* ``persist_insert`` runs, so two concurrent inserts of the same id resolve
        to exactly one winner and one ``DataAlreadyExist``. The non-materialized modes can
        only offer a best-effort ``exists_remote`` precondition instead - the store, not
        this process, holds the truth there."""
        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            # Claimed under the load lock - the Python analogue of the Java engine's
            # putIfAbsent - so two concurrent inserts of the same id resolve to exactly
            # one winner; the persist itself runs outside the lock, as in Java.
            with self._load_lock:
                if database_entry.id in self._entries:
                    raise DataAlreadyExist(database_entry.id)
                self._entries[database_entry.id] = database_entry
            self.persist_insert(database_entry)
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            if self.exists_remote(database_entry.id):
                raise DataAlreadyExist(database_entry.id)
            self.persist_insert(database_entry)
            self._cache.put(database_entry.id, database_entry)
        else:
            if self.exists_remote(database_entry.id):
                raise DataAlreadyExist(database_entry.id)
            self.persist_insert(database_entry)

        from database_driver.plugin.database_repository_registry import DatabaseRepositoryRegistry

        DatabaseRepositoryRegistry.log_bytes("The database entry contained %d Bytes", database_entry.document)

    def update(self, database_entry: DatabaseEntry) -> None:
        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            if database_entry.id not in self._entries:
                raise NoSuchEntryFound(database_entry.id)
            self.persist_update(database_entry)
            self._entries[database_entry.id] = database_entry
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            if not self.exists_remote(database_entry.id):
                raise NoSuchEntryFound(database_entry.id)
            self.persist_update(database_entry)
            self._cache.put(database_entry.id, database_entry)
        else:
            if not self.exists_remote(database_entry.id):
                raise NoSuchEntryFound(database_entry.id)
            self.persist_update(database_entry)

        from database_driver.plugin.database_repository_registry import DatabaseRepositoryRegistry

        DatabaseRepositoryRegistry.log_bytes("The database entry contained %d Bytes", database_entry.document)

    def delete(self, id: str) -> None:
        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            if id not in self._entries:
                raise NoSuchEntryFound(id)
            self.persist_delete(id)
            self._entries.pop(id, None)
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            if not self.exists_remote(id):
                raise NoSuchEntryFound(id)
            self.persist_delete(id)
            self._cache.invalidate(id)
        else:
            if not self.exists_remote(id):
                raise NoSuchEntryFound(id)
            self.persist_delete(id)

    def count(self) -> int:
        if self._config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            return len(self._entries)
        return self.count_remote()

    def clear(self) -> None:
        """In every mode the backing store is wiped via :meth:`clear_remote` first; the
        map-backed modes then mark themselves loaded-and-empty rather than unloaded - an
        empty section is a perfectly valid loaded state, and re-reading a store known to
        be empty would be a wasted load."""
        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self.clear_remote()
            with self._load_lock:
                self._entries.clear()
                self._loaded = True
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            self.clear_remote()
            self._cache.invalidate_all()
        else:
            self.clear_remote()

    def exists(self, id: str) -> bool:
        if self._config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            return id in self._entries
        return self.exists_remote(id)

    def find_entry_by_id(self, id: str) -> DatabaseEntry | None:
        with self._counter_lock:
            self._point_lookups += 1

        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            return self._entries.get(id)
        if mode is CacheMode.BOUNDED:
            return self._bounded_lookup(id)

        with self._counter_lock:
            self._point_loads += 1
        return self.fetch_one(id)

    def get_entries(self) -> list[DatabaseEntry]:
        """Materializes the complete section as a list in every mode - the historical
        contract, kept so existing consumers see no change. In ``BOUNDED``/``NONE`` this
        streams the backing store into a fresh list on every call (the cache is never the
        source: it could only contribute a partial, mixed-staleness view)."""
        if self._config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            return list(self._entries.values())

        collected: list[DatabaseEntry] = []
        self._stream_all(collected.append)
        return collected

    def for_each_entry(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Genuinely streaming in the non-materialized modes: ``BOUNDED`` and ``NONE``
        hand ``consumer`` straight to :meth:`load_all`, so a section of any size is
        walked in constant memory (each backend's ``load_all`` already reads in bounded
        batches). The map-backed modes iterate their in-memory view - already
        materialized, so streaming would save nothing."""
        if self._config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            for entry in list(self._entries.values()):
                consumer(entry)
        else:
            self._stream_all(consumer)

    def get_entries_page(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """The enumeration is ordered by entry id in every mode, so the same call yields
        the same page regardless of how the section is cached - a consumer can switch a
        section's ``CacheMode`` without its pagination changing meaning. The map-backed
        modes sort their in-memory view; the non-materialized modes delegate to
        :meth:`page_remote`, which backends override to push the paging into the store
        where they can."""
        if offset < 0:
            raise ValueError(
                f"@AbstractCachedDatabaseSection.get_entries_page: offset must not be negative, got {offset}"
            )
        if limit < 0:
            raise ValueError(
                f"@AbstractCachedDatabaseSection.get_entries_page: limit must not be negative, got {limit}"
            )
        if limit == 0:
            return []

        if self._config.cache_mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._ensure_loaded()
            ordered = sorted(self._entries.values(), key=lambda entry: entry.id)
            return ordered[offset : offset + limit]

        return self.page_remote(offset, limit)

    def page_remote(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Reads one id-ordered page (``[offset, offset + limit)``) from the backing
        store, for the modes that hold no materialized view. This default is the
        order-stable stream-and-skip for backends without native paging: one
        :meth:`load_all` pass through a bounded window (a max-heap of the
        ``offset + limit`` smallest ids), so memory is bounded by the page's end position
        rather than the section's size, and no entry is read twice. Backends whose store
        can order and slice natively (SQL, MongoDB, RethinkDB ...) override this and push
        the whole page down instead - always in ascending id order, the contract
        :meth:`get_entries_page` promises across every mode and backend.

        Callers paging *deeply* into an unsorted backend should know the window grows
        with ``offset``; for a full sequential sweep, :meth:`for_each_entry` is the
        constant-memory tool.
        """
        window_size = offset + limit

        # Max-heap on id: after the pass it holds the window_size smallest ids, i.e.
        # every entry that could possibly fall into the requested page.
        window: list[_MaxHeapById] = []

        def collect(entry: DatabaseEntry) -> None:
            heapq.heappush(window, _MaxHeapById(entry))
            if len(window) > window_size:
                heapq.heappop(window)

        self._stream_all(collect)

        if offset >= len(window):
            return []

        ascending = sorted((item.entry for item in window), key=lambda entry: entry.id)
        return ascending[offset:]

    # --------------------------------------------------------------- internals

    def _ensure_loaded(self) -> None:
        """The map-backed modes' one-time load: on the first data access (or the first
        after a ``LAZY`` :meth:`reload`), streams the whole backing store into the
        in-memory view. Double-checked on the ``_loaded`` flag so the warm path costs one
        read, with the load lock making concurrent first readers share a single load."""
        if self._loaded:
            return
        with self._load_lock:
            if self._loaded:
                return
            entries = self._entries
            assert entries is not None
            entries.clear()
            self._stream_all(lambda entry: entries.__setitem__(entry.id, entry))
            self._loaded = True

    def _bounded_lookup(self, id: str) -> DatabaseEntry | None:
        """``BOUNDED``'s read path: a cache hit is served from memory, a miss runs
        :meth:`_load_entry` once even under concurrent misses (the cache's stampede
        protection) and caches the result. The loader signals a missing row by failing
        with ``NoSuchEntryFound``, translated back to ``None`` here; the underlying cache
        does not retain failed loads, so absence is re-checked against the backing store
        on every call (no negative caching) - an entry inserted by another process
        becomes visible immediately.

        Runs the async cache on a private event loop, since this - like every engine
        read - is a synchronous method typically executing on a worker thread; the direct
        analogue of the Java engine ``join()``-ing the cache's future.
        """
        assert self._cache is not None
        try:
            return asyncio.run(self._cache.get(id))
        except NoSuchEntryFound:
            return None

    async def _load_entry(self, id: str) -> DatabaseEntry:
        """``BOUNDED``'s cache loader: a point read via :meth:`fetch_one`. Fails with
        ``NoSuchEntryFound`` for a missing row - the ``Cache`` contract forbids ``None``
        values, and a failed load is deliberately not cached (see
        :meth:`_bounded_lookup`)."""
        with self._counter_lock:
            self._point_loads += 1
        entry = self.fetch_one(id)
        if entry is None:
            raise NoSuchEntryFound(id)
        return entry

    def _stream_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Runs :meth:`load_all` with the engine's bookkeeping around it - every
        whole-section pass the engine itself triggers goes through here, so
        :meth:`stats` can report how often this section is fully read and what each pass
        costs. Backend paging pushdowns deliberately bypass this: a bounded page is not a
        full load."""
        started_at = time.perf_counter_ns()
        try:
            self.load_all(consumer)
        finally:
            with self._counter_lock:
                self._full_loads += 1
                self._full_load_nanos += time.perf_counter_ns() - started_at

    def stats(self) -> SectionStats:
        """This section's activity counters, snapshotted at the time of the call. A
        snapshot taken mid-operation may be off by the operation in flight - fine for the
        monitoring this exists for."""
        with self._counter_lock:
            loads = self._point_loads
            lookups = self._point_lookups
            return SectionStats(max(0, lookups - loads), loads, self._full_loads, self._full_load_nanos)

    def on_external_invalidate(self, id: str) -> None:
        """Drops whatever this engine holds in memory for ``id`` - no backend call, no
        re-fetch. This is the eviction hook a multi-instance consumer wires to its
        change-feed of choice (e.g. PostgreSQL ``LISTEN``/``NOTIFY``, or the Redis change
        channel this library's own sections publish on) so entries changed by *another*
        process stop being served stale; the listener infrastructure itself deliberately
        lives with the consumer, not here.

        Mode caveat, worth reading before wiring: in ``BOUNDED`` the next read of ``id``
        transparently re-fetches through the cache loader - the intended pairing. In the
        map-backed modes the dropped id simply reads as *absent* until the next full
        (re)load, because their reads never consult the backing store; for an external
        *update* (rather than delete) a :meth:`reload` - or ``BOUNDED`` in the first
        place - is the right tool there. ``NONE`` holds nothing, so this is a no-op.
        """
        mode = self._config.cache_mode
        if mode in (CacheMode.FULL, CacheMode.LAZY):
            assert self._entries is not None
            self._entries.pop(id, None)
        elif mode is CacheMode.BOUNDED:
            assert self._cache is not None
            self._cache.invalidate(id)


# Re-exported under the engine's namespace so consumers find the stats type where the
# Java edition nests it (AbstractCachedDatabaseSection.SectionStats).
AbstractCachedDatabaseSection.SectionStats = SectionStats  # type: ignore[attr-defined]
