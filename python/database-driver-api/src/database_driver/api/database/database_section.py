"""Mirror of ``de.lino.database.database.DatabaseSection``."""

from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from collections.abc import Callable

from database_driver.api.database.entity.database_entry import DatabaseEntry


class DatabaseSection(ABC):
    """Represents a single logical grouping of ``DatabaseEntry`` objects within a
    ``DatabaseProvider`` (e.g. a SQL table, a MongoDB collection, a Redis key prefix or a
    directory of JSON files) and exposes CRUD operations over its entries.

    Every synchronous operation declared here has a corresponding ``*_async`` coroutine
    method that executes the same logic on a worker thread via ``asyncio.to_thread`` -
    the direct analogue of the Java edition's ``CompletableFuture.runAsync`` default
    methods, which likewise offload the blocking call to a shared pool rather than being
    natively non-blocking.
    """

    @abstractmethod
    def get_name(self) -> str:
        """Returns the section's name."""

    @abstractmethod
    def insert(self, database_entry: DatabaseEntry) -> None:
        """Inserts a new json document into the database.

        Raises:
            DataAlreadyExist: If an entry with the same id already exists.
        """

    @abstractmethod
    def update(self, database_entry: DatabaseEntry) -> None:
        """Updates an existing json document in the database.

        Raises:
            NoSuchEntryFound: If no entry exists under the given entry's id.
        """

    @abstractmethod
    def delete(self, id: str) -> None:
        """Deletes an existing json document from the database by primary key.

        Raises:
            NoSuchEntryFound: If no entry exists under ``id``.
        """

    @abstractmethod
    def count(self) -> int:
        """Returns the number of entries currently stored in this section."""

    @abstractmethod
    def clear(self) -> None:
        """Clears this database section."""

    @abstractmethod
    def reload(self) -> None:
        """Discards this section's own cached view of its entries and rebuilds it from
        the backing store, picking up entries added, changed or removed by something other
        than this section itself (e.g. a backup restored directly onto disk while this
        section was already loaded).

        Every implementation shipped by the plugin module caches its entries in memory
        beyond what each write already keeps in sync (see each implementation's own
        class-level documentation), so a genuine re-read of the backing store is required
        here, not a no-op - :meth:`get_entries` and friends would otherwise never reflect
        a change made outside this section.
        """

    @abstractmethod
    def exists(self, id: str) -> bool:
        """Returns whether a json document exists under the given primary key."""

    @abstractmethod
    def find_entry_by_id(self, id: str) -> DatabaseEntry | None:
        """Finds a matching json document by primary key.

        Returns:
            The matching ``DatabaseEntry``, or ``None`` if no entry exists under ``id`` -
            standing in for the Java edition's ``Optional``.
        """

    @abstractmethod
    def get_entries(self) -> list[DatabaseEntry]:
        """Returns a list of all database entries.

        The whole section is materialized as one list, whatever its size - kept that way
        for compatibility. A consumer working through a large section should prefer
        :meth:`for_each_entry` (constant memory) or :meth:`get_entries_page` (one bounded
        page at a time) instead of holding every entry at once.
        """

    def for_each_entry(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Streams every entry of this section to ``consumer``, one at a time, without
        materializing the section as a whole - the constant-memory alternative to
        :meth:`get_entries` for sections too large to hold in one list. No entry order is
        guaranteed.

        This default implementation exists only so ``DatabaseSection`` implementations
        written before this method keep working - it simply iterates
        :meth:`get_entries` and therefore still materializes everything. Every section
        shipped by the plugin module overrides it with a genuinely streaming
        implementation.
        """
        for entry in self.get_entries():
            consumer(entry)

    def get_entries_page(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """A single page of this section's entries: the entries at positions
        ``[offset, offset + limit)`` of a stable, id-ordered enumeration - the same page
        for the same arguments as long as the data does not change, so a consumer can
        work through a large section chunk by chunk without ever holding more than one
        page.

        This default implementation exists only so ``DatabaseSection`` implementations
        written before this method keep working - it slices :meth:`get_entries` (in that
        list's order) and therefore still materializes everything. Every section shipped
        by the plugin module overrides it with an implementation that pushes the paging
        toward the backing store instead.

        Raises:
            ValueError: If ``offset`` or ``limit`` is negative.
        """
        if offset < 0:
            raise ValueError(f"@DatabaseSection.get_entries_page: offset must not be negative, got {offset}")
        if limit < 0:
            raise ValueError(f"@DatabaseSection.get_entries_page: limit must not be negative, got {limit}")

        all_entries = self.get_entries()
        if limit == 0 or offset >= len(all_entries):
            return []
        return list(all_entries[offset : offset + limit])

    # ------------------------------------------------------------------ async

    async def insert_async(self, database_entry: DatabaseEntry) -> None:
        """Executes the :meth:`insert` process async."""
        await asyncio.to_thread(self.insert, database_entry)

    async def update_async(self, database_entry: DatabaseEntry) -> None:
        """Executes the :meth:`update` process async."""
        await asyncio.to_thread(self.update, database_entry)

    async def delete_async(self, id: str) -> None:
        """Executes the :meth:`delete` process async."""
        await asyncio.to_thread(self.delete, id)

    async def count_async(self) -> int:
        """Executes the :meth:`count` process async."""
        return await asyncio.to_thread(self.count)

    async def clear_async(self) -> None:
        """Executes the :meth:`clear` section process async."""
        await asyncio.to_thread(self.clear)

    async def exists_async(self, id: str) -> bool:
        """Executes the :meth:`exists` process async."""
        return await asyncio.to_thread(self.exists, id)

    async def find_entry_by_id_async(self, id: str) -> DatabaseEntry | None:
        """Executes the :meth:`find_entry_by_id` process async."""
        return await asyncio.to_thread(self.find_entry_by_id, id)

    async def get_entries_async(self) -> list[DatabaseEntry]:
        """Executes the :meth:`get_entries` process async."""
        return await asyncio.to_thread(self.get_entries)

    async def for_each_entry_async(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Executes the :meth:`for_each_entry` process async; ``consumer`` runs on the
        worker thread."""
        await asyncio.to_thread(self.for_each_entry, consumer)

    async def get_entries_page_async(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Executes the :meth:`get_entries_page` process async."""
        return await asyncio.to_thread(self.get_entries_page, offset, limit)

    async def reload_async(self) -> None:
        """Executes the :meth:`reload` process async."""
        await asyncio.to_thread(self.reload)
