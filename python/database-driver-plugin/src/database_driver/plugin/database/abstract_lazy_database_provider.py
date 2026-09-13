"""Mirror of ``de.lino.database.database.AbstractLazyDatabaseProvider``."""

from __future__ import annotations

import threading
from abc import abstractmethod
from collections.abc import Callable

from database_driver.api.database.database_provider import DatabaseProvider
from database_driver.api.database.database_section import DatabaseSection
from database_driver.api.database.section_config import SectionConfig

from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection


class AbstractLazyDatabaseProvider(DatabaseProvider):
    """The single shared section-management logic behind every ``DatabaseProvider``
    shipped by this module. Historically each provider eagerly constructed one section
    object per backend table the moment the provider itself was constructed - and since
    constructing a section loaded its entire contents into memory, connecting to a
    database cost time and memory proportional to *everything stored in it*, whether or
    not the application would ever touch it. This class makes provider construction
    O(number of sections) instead: discovery only records section *names*, and section
    objects are constructed - and, for ``FULL``, warmed - on first demand.

    A backend provider only supplies its three storage-side operations
    (:meth:`discover_names`, :meth:`construct_section`, :meth:`drop_section_remote`) plus
    its own ``shutdown``; everything else - memoization, per-section ``SectionConfig``
    bookkeeping, replace-on-reconfiguration, the ``DatabaseProvider`` section contract -
    is implemented here once.
    """

    def __init__(self) -> None:
        # Every section name currently known to exist: the names found by the last
        # discover_names pass plus every name created through create_section since. This
        # set - not the instance map below - is what exists_section and enumeration
        # consult, which is precisely what lets a section *exist* without ever having
        # been constructed.
        self._discovered_names: set[str] = set()

        # The section instances constructed so far, keyed by name - a memo, not the
        # source of truth: a discovered name absent from this map is simply a section
        # nobody asked for yet.
        self._sections: dict[str, AbstractCachedDatabaseSection] = {}

        # The SectionConfig most recently declared per section name. Kept separately from
        # the instances so a section materialized later - even after a reload dropped its
        # instance - comes back under the configuration its creator declared rather than
        # silently reverting to SectionConfig.full().
        self._section_configs: dict[str, SectionConfig] = {}

        # Serializes create_section's compute-style check-and-replace and materialize's
        # compute-if-absent - the Python analogue of the Java maps' atomic per-key
        # operations.
        self._sections_lock = threading.Lock()

    # ------------------------------------------------------------- primitives

    @abstractmethod
    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Streams the name of every section currently present in the backing store to
        ``consumer`` - the backend's table/collection/directory/key listing, and nothing
        more: implementations must not construct section objects or read row data here,
        since this runs on every :meth:`reload` and at provider construction, exactly the
        moments the historical row loading made ruinously expensive."""

    @abstractmethod
    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        """Constructs the backend's section object for ``name`` under ``config``.
        Creation of the backend-side container (a ``CREATE TABLE IF NOT EXISTS``, a
        directory, a file) belongs in the section's constructor as before, but the
        constructor must not load row data - the engine decides if and when rows are
        read, and this provider triggers the ``FULL`` warm-up itself where the contract
        requires it."""

    @abstractmethod
    def drop_section_remote(self, name: str) -> None:
        """Removes the section's backing container itself from the store (a
        ``DROP TABLE``, a directory delete, a key-prefix wipe ...), for
        :meth:`delete_section`. Storage only - the local bookkeeping is this class'
        job."""

    # ------------------------------------------------------- DatabaseProvider

    def reload(self) -> None:
        """Rebuilds only the *name* set from the backing store and drops every memoized
        section instance; no section objects are constructed and no row data is read -
        the cost of a reload is one backend listing, regardless of how much data the
        store holds. Previously obtained section instances are unaffected (they keep
        serving their own, now-detached state, exactly as this method's contract has
        always promised); re-fetching a section via :meth:`get_section` materializes a
        fresh instance reflecting the store."""
        self._sections.clear()
        self._discovered_names.clear()
        self.discover_names(self._discovered_names.add)

    def create_section(self, name: str, config: SectionConfig | None = None) -> DatabaseSection:
        """Records ``config`` as ``name``'s configuration (defaulting to the last
        declared one, or ``SectionConfig.full()`` - the historical warm-at-creation
        behavior - when none ever was), constructs the section if it was never
        materialized (or replaces the instance if it currently runs under a *different*
        configuration - the newest declaration wins, and the replaced instance's cache
        state is dropped with it), and, for ``FULL``, warms the returned section
        synchronously so it is loaded by the time this returns - the timing every
        pre-existing consumer was built against."""
        if config is None:
            config = self._section_configs.get(name, SectionConfig.full())

        self._section_configs[name] = config
        self._discovered_names.add(name)

        with self._sections_lock:
            existing = self._sections.get(name)
            if existing is not None and existing.get_config() == config:
                section = existing
            else:
                section = self.construct_section(name, config)
                self._sections[name] = section

        section.warm_up()
        return section

    def delete_section(self, name: str) -> None:
        self.drop_section_remote(name)
        self._sections.pop(name, None)
        self._discovered_names.discard(name)
        self._section_configs.pop(name, None)

    def exists_section(self, name: str) -> bool:
        return name in self._discovered_names

    def get_sections(self) -> list[DatabaseSection]:
        """Materializes an instance for every discovered name first - but materialization
        is cheap by design (no row data is read; even a ``FULL`` section obtained this
        way loads only on its first data access), so enumerating a database's sections no
        longer implies loading the database."""
        for name in list(self._discovered_names):
            self._materialize(name)
        return list(self._sections.values())

    def get_section(self, name: str) -> DatabaseSection | None:
        existing = self._sections.get(name)
        if existing is not None:
            return existing
        if name not in self._discovered_names:
            return None
        return self._materialize(name)

    def clear(self) -> None:
        """Clears every discovered section's backing store - materializing cold instances
        as needed, which stays cheap since clearing never requires loading - and then
        forgets all local section state, matching the historical behavior where a cleared
        provider reports no sections until they are re-created or re-discovered via
        :meth:`reload`."""
        for section in self.get_sections():
            section.clear()
        self.forget_sections()

    def forget_sections(self) -> None:
        """Drops every piece of local section bookkeeping (instances, names,
        configurations) without touching the backing store - the local half of
        :meth:`clear`, also used by subclasses' ``shutdown`` implementations."""
        self._sections.clear()
        self._discovered_names.clear()
        self._section_configs.clear()

    def _materialize(self, name: str) -> AbstractCachedDatabaseSection:
        """Returns ``name``'s memoized section instance, constructing it under its
        declared (or default ``SectionConfig.full()``) configuration if this is the first
        demand for it. Deliberately does *not* warm the result: the warm-at-creation
        contract belongs to :meth:`create_section` alone, and a ``FULL`` section
        materialized here loads itself on first data access instead."""
        with self._sections_lock:
            existing = self._sections.get(name)
            if existing is not None:
                return existing
            section = self.construct_section(name, self._section_configs.get(name, SectionConfig.full()))
            self._sections[name] = section
            return section
