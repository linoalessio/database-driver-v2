"""Mirror of ``de.lino.database.database.sql.SQLDatabaseSection``."""

from __future__ import annotations

import asyncio
import traceback
from collections.abc import Callable
from typing import Any, Optional

from database_driver.api.database.database_type import DatabaseType
from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.sql.sql_execution import SQLExecution

_RELOAD_FETCH_SIZE = 256
"""How many rows ``load_all`` fetches from the server per round trip. Bounds a full
load's transient memory to roughly this many rows' raw bytes on top of whatever the
engine builds from them - without it, a driver that buffers the complete result set in
memory turns loading a multi-gigabyte table into a boot-time out-of-memory failure."""


class SQLDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one SQL table, shared by every SQL vendor this
    driver supports. All caching lives in ``AbstractCachedDatabaseSection``; this class
    only supplies the table's storage primitives - each one a single parameterized
    statement against the ``(id, data)`` schema the constructor creates.

    Creates (if not already present) this section's table at construction; no row is
    read here - whether and when rows are loaded is the engine's decision per ``config``,
    with the owning provider triggering the ``FULL`` warm-up right after construction.
    Constructing without an explicit ``config`` mirrors the historical Java constructor:
    ``FULL`` and warmed immediately.
    """

    def __init__(
        self,
        database_type: DatabaseType,
        name: str,
        sql_execution: SQLExecution,
        config: SectionConfig | None = None,
    ) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        self.sql_execution = sql_execution
        # Kept because vendors disagree on paging syntax - page_remote must emit
        # "LIMIT ? OFFSET ?" for one family and "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
        # for the other - and on their BLOB column type below.
        self.database_type = database_type

        blob_type = {
            DatabaseType.POSTGRES_SQL: "BYTEA",
            DatabaseType.MY_SQL: "LONGBLOB",
            DatabaseType.MARIA_DB: "LONGBLOB",
            DatabaseType.MICROSOFT_SQL_SERVER: "VARBINARY(MAX)",
        }.get(database_type, "BLOB")

        self.sql_execution.execute_update(f"CREATE TABLE IF NOT EXISTS {name} (id TEXT, data {blob_type});")

        if direct:
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Streams ``SELECT * FROM <table>`` in bounded batches via ``fetchmany``, never
        buffering the result set whole - see ``SQLExecution.execute_streaming_query`` for
        why the plain variant would exhaust memory on a large table."""

        def stream(cursor: Any) -> bool:
            while True:
                rows = cursor.fetchmany(_RELOAD_FETCH_SIZE)
                if not rows:
                    return True
                for row in rows:
                    entry = _read_entry(row[0], row[1])
                    if entry is not None:
                        consumer(entry)

        self.sql_execution.execute_streaming_query(
            f"SELECT id, data FROM {self.get_name()}", _RELOAD_FETCH_SIZE, stream, True
        )

    def fetch_one(self, id: str) -> Optional[DatabaseEntry]:
        """A single indexed-lookup-shaped ``SELECT ... WHERE id = ?``. A row whose
        ``data`` column is unexpectedly ``NULL`` surfaces as absent rather than raising,
        because ``execute_query``'s error handling maps any failure inside the row mapper
        to the default value."""

        def first(cursor: Any) -> Optional[DatabaseEntry]:
            row = cursor.fetchone()
            if row is None:
                return None
            return _read_entry(id, row[0])

        return self.sql_execution.execute_query(
            f"SELECT data FROM {self.get_name()} WHERE id = ?", first, None, id
        )

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        self.sql_execution.execute_update(
            f"INSERT INTO {self.get_name()} (id, data) VALUES (?, ?);",
            database_entry.id,
            database_entry.document.to_bytes(),
        )

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        self.sql_execution.execute_update(
            f"UPDATE {self.get_name()} SET data = ? WHERE id = ?",
            database_entry.document.to_bytes(),
            database_entry.id,
        )

    def persist_delete(self, id: str) -> None:
        self.sql_execution.execute_update(f"DELETE FROM {self.get_name()} WHERE id = ?", id)

    def count_remote(self) -> int:
        def count(cursor: Any) -> int:
            row = cursor.fetchone()
            return int(row[0]) if row else 0

        return self.sql_execution.execute_query(f"SELECT COUNT(*) FROM {self.get_name()}", count, 0)

    def exists_remote(self, id: str) -> bool:
        def exists(cursor: Any) -> bool:
            return cursor.fetchone() is not None

        return self.sql_execution.execute_query(
            f"SELECT 1 FROM {self.get_name()} WHERE id = ?", exists, False, id
        )

    def clear_remote(self) -> None:
        # SQLite has no TRUNCATE statement at all - the one place the vendors' DDL
        # dialects force a branch here. (The Java edition issues TRUNCATE unconditionally
        # and relies on executeUpdate's swallow-and-log for SQLite, which leaves the rows
        # in place; a silently non-clearing clear() is a bug, not a semantic to mirror.)
        if self.database_type is DatabaseType.SQLITE:
            self.sql_execution.execute_update(f"DELETE FROM {self.get_name()}")
        else:
            self.sql_execution.execute_update(f"TRUNCATE TABLE {self.get_name()}")

    def page_remote(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Pushed down entirely: the server orders by id and returns only the requested
        window, so a page costs one bounded query no matter how large the table is.
        Vendors split over the paging clause - MySQL, MariaDB, PostgreSQL and SQLite take
        ``LIMIT ? OFFSET ?``, while Oracle and Microsoft SQL Server take the SQL-standard
        ``OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`` - which is the one reason this class
        still needs to know its ``database_type`` after construction."""
        if self.database_type in (DatabaseType.ORACLE, DatabaseType.MICROSOFT_SQL_SERVER, DatabaseType.APACHE_DERBY):
            query = f"SELECT id, data FROM {self.get_name()} ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
            bindings: tuple[Any, ...] = (offset, limit)
        else:
            query = f"SELECT id, data FROM {self.get_name()} ORDER BY id LIMIT ? OFFSET ?"
            bindings = (limit, offset)

        def page(cursor: Any) -> list[DatabaseEntry]:
            collected: list[DatabaseEntry] = []
            for row in cursor.fetchall():
                entry = _read_entry(row[0], row[1])
                if entry is not None:
                    collected.append(entry)
            return collected

        return self.sql_execution.execute_query(query, page, [], *bindings)

    async def clear_async(self) -> None:
        await asyncio.to_thread(self.clear)


def _read_entry(id: str, data: Any) -> Optional[DatabaseEntry]:
    """Parses one row into a ``DatabaseEntry``, the one row shape (``data`` BLOB holding
    the serialized ``JsonDocument``) every query here shares.

    Raises:
        NoSuchDataFound: If the row exists but its ``data`` column is ``NULL``.
    """
    if data is None:
        raise NoSuchDataFound(id)
    try:
        payload = bytes(data) if not isinstance(data, bytes) else data
        return DatabaseEntry(id, JsonDocument(payload))
    except (ValueError, TypeError):
        traceback.print_exc()
        return None
