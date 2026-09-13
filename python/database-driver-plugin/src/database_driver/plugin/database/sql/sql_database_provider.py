"""Mirror of ``de.lino.database.database.sql.SQLDatabaseProvider``."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from typing import Any

from database_driver.api.database.database_type import DatabaseType
from database_driver.api.database.section_config import SectionConfig

from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.sql.sql_database_section import SQLDatabaseSection
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class SQLDatabaseProvider(AbstractLazyDatabaseProvider):
    """The shared ``DatabaseProvider`` implementation behind every SQL vendor this
    driver supports (MySQL, PostgreSQL, MariaDB, SQLite, Oracle, Microsoft SQL Server -
    see the vendor-specific subclasses in the sibling packages), each section mapping to
    one table, all sharing this database's single ``SQLExecution`` connection pool.
    Section lifecycle and caching live in ``AbstractLazyDatabaseProvider``; this class
    only supplies the vendor-aware storage operations - listing tables, constructing a
    ``SQLDatabaseSection``, dropping a table."""

    def __init__(self, database_type: DatabaseType, sql_execution: SQLExecution) -> None:
        super().__init__()
        self.database_type = database_type
        self._sql_execution = sql_execution
        self.reload()

    def get_sql_execution(self) -> SQLExecution:
        """The connection pool this database runs every query and update through -
        exposed as the deliberate raw-SQL escape hatch for a consumer feature that
        genuinely cannot be expressed over ``DatabaseSection``'s key/value surface (the
        motivating case: a Postgres ``tsvector``/GIN full-text search index, which needs
        vendor-specific column types and index DDL no generic section can carry).
        Callers share this pool with every section of this database: never call
        ``SQLExecution.shutdown()`` on it - the provider owns its lifecycle - and keep
        statements short-lived so section traffic is never starved of pooled
        connections."""
        return self._sql_execution

    def shutdown(self) -> None:
        self._sql_execution.shutdown()
        self.forget_sections()

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Runs the vendor-specific table-listing query, streaming each table name to
        ``consumer``."""

        def collect(cursor: Any) -> bool:
            for row in cursor.fetchall():
                consumer(row[0])
            return True

        self._sql_execution.execute_query(_pattern(self.database_type), collect, True)

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return SQLDatabaseSection(self.database_type, name, self._sql_execution, config)

    def drop_section_remote(self, name: str) -> None:
        self._sql_execution.execute_update(f"DROP TABLE {name}")

    async def clear_async(self) -> None:
        """Clears every section concurrently rather than one table at a time, since
        every section shares the same ``SQLExecution`` connection pool - which is itself
        built for concurrent multi-threaded use - so clearing them in parallel is no less
        safe than clearing them sequentially, just faster; overrides
        ``DatabaseProvider``'s default, which would otherwise run the whole sequential
        :meth:`clear` on a single worker thread."""
        await asyncio.gather(*(section.clear_async() for section in self.get_sections()))
        self.forget_sections()


def _pattern(database_type: DatabaseType) -> str:
    """Builds the vendor-specific query that lists every existing table's name, used by
    ``discover_names`` to enumerate this database's sections.

    Two deliberate deviations from the Java edition's patterns: PostgreSQL's
    ``information_schema`` stores schema names lowercase (``'public'``, not
    ``'PUBLIC'``), and MySQL/MariaDB scope tables by database (``DATABASE()``), not by a
    ``PUBLIC`` schema - the Java queries return empty on those vendors.
    """
    if database_type is DatabaseType.SQLITE:
        return "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    if database_type is DatabaseType.MICROSOFT_SQL_SERVER:
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='dbo'"
    if database_type is DatabaseType.ORACLE:
        return "SELECT table_name FROM user_tables"
    if database_type is DatabaseType.POSTGRES_SQL:
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='public'"
    if database_type in (DatabaseType.MY_SQL, DatabaseType.MARIA_DB):
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE()"
    return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'"
