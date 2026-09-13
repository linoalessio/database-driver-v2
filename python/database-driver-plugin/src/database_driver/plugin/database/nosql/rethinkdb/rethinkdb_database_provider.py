"""Mirror of ``de.lino.database.database.nosql.rethinkdb.RethinkDBDatabaseProvider``."""

from __future__ import annotations

from collections.abc import Callable

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.section_config import SectionConfig
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.rethinkdb.rethinkdb_database_section import RethinkDBDatabaseSection


class RethinkDBDatabaseProvider(AbstractLazyDatabaseProvider):
    """The ``DatabaseProvider`` backed by a RethinkDB database, each section a table via
    ``RethinkDBDatabaseSection``, all sharing this database's single connection. Section
    lifecycle and caching live in ``AbstractLazyDatabaseProvider``; this class only
    supplies the table-level storage operations - listing tables, constructing a
    section, dropping a table.

    Connects with ``credentials`` and discovers every existing table's name. Only names
    - no section objects, no rows - so construction cost is one ``table_list`` query,
    independent of how much the database holds.
    """

    def __init__(self, credentials: Credentials) -> None:
        super().__init__()

        from rethinkdb import RethinkDB

        self.r = RethinkDB()
        self.connection = self.r.connect(
            host=credentials.address,
            port=credentials.port,
            user=credentials.user_name,
            password=credentials.password,
            db=credentials.database,
        )
        self.db = self.r.db(credentials.database)

        self.reload()

    def shutdown(self) -> None:
        self.connection.close()
        self.forget_sections()

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Runs the database's ``table_list`` query, streaming each table name to
        ``consumer``."""
        for name in self.db.table_list().run(self.connection):
            consumer(name)

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return RethinkDBDatabaseSection(name, self.r, self.connection, self.db, config)

    def drop_section_remote(self, name: str) -> None:
        self.db.table_drop(name).run(self.connection)
