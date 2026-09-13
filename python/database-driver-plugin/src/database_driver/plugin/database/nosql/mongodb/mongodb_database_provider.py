"""Mirror of ``de.lino.database.database.nosql.mongodb.MongoDBDatabaseProvider``."""

from __future__ import annotations

import urllib.parse
from collections.abc import Callable

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.section_config import SectionConfig
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.mongodb.mongodb_database_section import MongoDBDatabaseSection

_FORBIDDEN = ["system.version", "system.users"]
"""Collection names that are never exposed as a section, since they are MongoDB-internal
rather than application data."""


class MongoDBDatabaseProvider(AbstractLazyDatabaseProvider):
    """The ``DatabaseProvider`` backed by a MongoDB database, each section a collection
    via ``MongoDBDatabaseSection``. Section lifecycle and caching live in
    ``AbstractLazyDatabaseProvider``; this class only supplies the collection-level
    storage operations - listing collections, constructing a section, dropping a
    collection. pymongo's ``MongoClient`` is itself thread-safe and designed for
    concurrent multi-threaded use, so every method here is safe to call concurrently
    without additional locking.

    Connects with ``credentials`` and discovers every existing, non-internal
    collection's name. Only names - no section objects, no documents - so construction
    cost is one collection listing, independent of how much the database holds.
    """

    def __init__(self, credentials: Credentials) -> None:
        super().__init__()

        from pymongo import MongoClient

        self.mongo_client = MongoClient(
            "mongodb://{user}:{password}@{address}:{port}/{database}".format(
                user=credentials.user_name,
                password=urllib.parse.quote(credentials.password, safe=""),
                address=credentials.address,
                port=credentials.port,
                database=credentials.database,
            )
        )
        self.mongo_database = self.mongo_client[credentials.database]

        self.reload()

    def shutdown(self) -> None:
        self.mongo_client.close()
        self.forget_sections()

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Lists the database's collection names, skipping the MongoDB-internal ones."""
        for name in self.mongo_database.list_collection_names():
            if name in _FORBIDDEN:
                continue
            consumer(name)

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return MongoDBDatabaseSection(self.mongo_database, name, config)

    def drop_section_remote(self, name: str) -> None:
        self.mongo_database[name].drop()
