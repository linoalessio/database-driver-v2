"""Mirror of ``de.lino.database.DatabaseRepository``."""

from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from typing import ClassVar

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_provider import DatabaseProvider
from database_driver.api.database.database_type import DatabaseType
from database_driver.api.utils.pair import Pair


class DatabaseRepository(ABC):
    """Central entry point for managing the pool of registered ``DatabaseProvider``
    instances.

    A single ``DatabaseRepository`` instance is expected to exist per application and is
    exposed through :meth:`get_instance` once a concrete subclass has installed itself
    via :meth:`set_instance`. Implementations are responsible for tracking registered
    providers by numeric id, creating new providers for a given ``DatabaseType`` and
    ``Credentials``, and converting data between two registered providers.

    Every synchronous operation declared here has a corresponding ``*_async`` coroutine
    method that executes the same logic on a worker thread via ``asyncio.to_thread`` -
    the direct analogue of the Java edition's ``CompletableFuture`` default methods.
    """

    _instance: ClassVar[DatabaseRepository | None] = None

    @classmethod
    def get_instance(cls) -> DatabaseRepository | None:
        """Returns the globally accessible instance, or ``None`` if none was installed."""
        return DatabaseRepository._instance

    @classmethod
    def set_instance(cls, instance: DatabaseRepository) -> None:
        """Installs the given repository as the globally accessible instance returned by
        :meth:`get_instance`. Reserved for concrete subclasses, mirroring the Java
        edition's ``protected`` access."""
        DatabaseRepository._instance = instance

    @abstractmethod
    def get_database_provider_pool(self, database_type: DatabaseType | None = None) -> list[DatabaseProvider]:
        """Returns a list of all registered databases, optionally filtered to those of
        the given type. (The Java edition splits this into two overloads; Python folds
        them into one optional parameter.)"""

    @abstractmethod
    def shutdown(self) -> None:
        """Shuts down all running database providers."""

    @abstractmethod
    def convert(self, source_id: int, target_id: int) -> Pair[DatabaseProvider, DatabaseProvider]:
        """Converts the content of a specific database to another one.

        Args:
            source_id: Id of the database that shall be used as a resource database.
            target_id: Id of the database that will be used as a destination database.

        Returns:
            A :class:`Pair` of the two providers involved, the first one being the
            source, the second one being the destination.
        """

    @abstractmethod
    def find_database_provider_by_id(self, id: int) -> DatabaseProvider | None:
        """Returns the database registered under ``id``, or ``None`` if none is -
        standing in for the Java edition's ``Optional``."""

    @abstractmethod
    def register_database_provider(
        self, id: int, database_type: DatabaseType, credentials: Credentials
    ) -> DatabaseProvider:
        """Registers a new database.

        Args:
            id: Id of the database.
            database_type: Database type.
            credentials: Login credentials.

        Returns:
            The newly created and registered ``DatabaseProvider``.
        """

    @abstractmethod
    def unregister_database_provider(self, id: int) -> DatabaseProvider:
        """Shuts down a specific database and unregisters it from the repository.

        Returns:
            The ``DatabaseProvider`` that was shut down and unregistered.
        """

    # ------------------------------------------------------------------ async

    async def get_database_provider_pool_async(
        self, database_type: DatabaseType | None = None
    ) -> list[DatabaseProvider]:
        """Executes the :meth:`get_database_provider_pool` process async."""
        return await asyncio.to_thread(self.get_database_provider_pool, database_type)

    async def shutdown_async(self) -> None:
        """Executes the :meth:`shutdown` process async."""
        await asyncio.to_thread(self.shutdown)

    async def convert_async(self, source_id: int, target_id: int) -> Pair[DatabaseProvider, DatabaseProvider]:
        """Executes the :meth:`convert` process async."""
        return await asyncio.to_thread(self.convert, source_id, target_id)

    async def find_database_provider_by_id_async(self, id: int) -> DatabaseProvider | None:
        """Executes the :meth:`find_database_provider_by_id` process async."""
        return await asyncio.to_thread(self.find_database_provider_by_id, id)

    async def register_database_provider_async(
        self, id: int, database_type: DatabaseType, credentials: Credentials
    ) -> DatabaseProvider:
        """Executes the :meth:`register_database_provider` process async."""
        return await asyncio.to_thread(self.register_database_provider, id, database_type, credentials)

    async def unregister_database_provider_async(self, id: int) -> DatabaseProvider:
        """Executes the :meth:`unregister_database_provider` process async."""
        return await asyncio.to_thread(self.unregister_database_provider, id)
