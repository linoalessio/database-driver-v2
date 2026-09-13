"""Mirror of ``de.lino.database.database.DatabaseProvider``."""

from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod

from database_driver.api.database.database_section import DatabaseSection
from database_driver.api.database.section_config import SectionConfig


class DatabaseProvider(ABC):
    """Represents a single connected database backend (e.g. a MySQL instance, a MongoDB
    database or a local JSON file store) and exposes management operations over its
    ``DatabaseSection`` instances.

    A section roughly corresponds to a table, collection or directory depending on the
    concrete database technology. Every synchronous operation declared here has a
    corresponding ``*_async`` coroutine method that executes the same logic on a worker
    thread via ``asyncio.to_thread`` - the direct analogue of the Java edition's
    ``CompletableFuture`` default methods.
    """

    @abstractmethod
    def shutdown(self) -> None:
        """Shuts down the current database."""

    @abstractmethod
    def create_section(self, name: str, config: SectionConfig | None = None) -> DatabaseSection:
        """Creates a new database section if it does not exist; otherwise the existing
        section is returned.

        Called without ``config`` this always means :attr:`CacheMode.FULL` (unless a
        different ``SectionConfig`` was already registered for ``name``): the section
        holds every entry in memory, warm by the time this returns - the behavior every
        consumer written before per-section cache configuration existed was built
        against. (The Java edition splits this into two overloads; Python folds them into
        one optional parameter.)

        With an explicit ``config``, repeating the call with the configuration the
        section already runs under returns the existing instance; a *different*
        configuration replaces the instance - the newest declaration wins - dropping
        whatever cache state the old one held.
        """

    @abstractmethod
    def delete_section(self, name: str) -> None:
        """Deletes the section if it exists; otherwise the process is stopped."""

    @abstractmethod
    def exists_section(self, name: str) -> bool:
        """Returns whether a section exists under the given name."""

    @abstractmethod
    def get_sections(self) -> list[DatabaseSection]:
        """Returns a list of all existing database sections."""

    @abstractmethod
    def get_section(self, name: str) -> DatabaseSection | None:
        """Returns the section with the given name, or ``None`` if no section exists
        under it - standing in for the Java edition's ``Optional``."""

    @abstractmethod
    def clear(self) -> None:
        """Removes all sections from the database."""

    @abstractmethod
    def reload(self) -> None:
        """Discards this database's own cached view of which sections exist and rebuilds
        it from the backing store, picking up sections created or removed by something
        other than this database itself (e.g. a backup restored directly onto disk while
        this database was already running).

        Every implementation shipped by the plugin module caches its section list beyond
        what each write already keeps in sync, so a genuine re-read of the backing store
        is required here, not a no-op - :meth:`get_sections` / :meth:`get_section` would
        otherwise never reflect a change made outside this database. Does not, by itself,
        affect any ``DatabaseSection`` obtained from this database before the call, since
        the rebuilt section list holds entirely new instances; re-fetch it via
        :meth:`get_section` afterward.
        """

    # ------------------------------------------------------------------ async

    async def shutdown_async(self) -> None:
        """Executes the :meth:`shutdown` process async."""
        await asyncio.to_thread(self.shutdown)

    async def create_section_async(self, name: str, config: SectionConfig | None = None) -> DatabaseSection:
        """Executes the :meth:`create_section` process async."""
        return await asyncio.to_thread(self.create_section, name, config)

    async def delete_section_async(self, name: str) -> None:
        """Executes the :meth:`delete_section` process async."""
        await asyncio.to_thread(self.delete_section, name)

    async def exists_section_async(self, name: str) -> bool:
        """Executes the :meth:`exists_section` query process async."""
        return await asyncio.to_thread(self.exists_section, name)

    async def get_sections_async(self) -> list[DatabaseSection]:
        """Executes the :meth:`get_sections` collecting process async."""
        return await asyncio.to_thread(self.get_sections)

    async def get_section_async(self, name: str) -> DatabaseSection | None:
        """Executes the :meth:`get_section` process async."""
        return await asyncio.to_thread(self.get_section, name)

    async def clear_async(self) -> None:
        """Executes the :meth:`clear` process async."""
        await asyncio.to_thread(self.clear)

    async def reload_async(self) -> None:
        """Executes the :meth:`reload` process async."""
        await asyncio.to_thread(self.reload)
