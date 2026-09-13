"""Mirror of ``de.lino.database.database.sql.SQLExecution``."""

from __future__ import annotations

import asyncio
import queue
import threading
import traceback
from collections.abc import Callable
from typing import Any, TypeVar

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType

T = TypeVar("T")

_MAXIMUM_POOL_SIZE = 10
"""Mirrors the Java edition's Hikari ``maximumPoolSize``."""


class SQLExecution:
    """A single DB-API connection pool shared by every ``SQLDatabaseSection`` of one
    ``DatabaseProvider``, plus the parameterized query/update helpers built on top of it
    - the Python counterpart of the Java edition's HikariCP-backed helper.

    HikariCP's role (bounded pool, lazy connection creation, thread-safe checkout) is
    played by a ``queue.Queue`` of connections created on demand up to
    ``_MAXIMUM_POOL_SIZE``; DB-API connections themselves are not generally thread-safe,
    so exactly the checkout discipline Hikari enforces - one connection per operation,
    returned when done - is enforced here too. Every method is safe to call concurrently.

    Queries are written with JDBC-style ``?`` placeholders throughout the SQL layer, and
    translated to the vendor driver's paramstyle (``qmark`` or ``format``) on execution -
    what lets one ``SQLDatabaseSection`` serve every vendor, exactly as in Java.

    Error handling mirrors the Java edition's convention: ``execute_update`` and the
    query helpers log a failed statement and fall back to the default value rather than
    raising, while :meth:`execute_transaction` raises - its callers are the ones
    positioned to decide whether a partially-applied DDL sequence is recoverable.
    """

    def __init__(self, database_type: DatabaseType, credentials: Credentials) -> None:
        """Builds a connection pool for ``database_type``, configured with
        ``credentials``. The actual driver import happens lazily inside the vendor's
        connection factory, so unused backends cost nothing at import time."""
        self._connect, self._paramstyle = _connection_factory(database_type, credentials)
        self._pool: queue.Queue = queue.Queue()
        self._created = 0
        self._created_lock = threading.Lock()
        self._closed = False

    def shutdown(self) -> None:
        """Closes every pooled connection. No further queries or updates should be
        issued after this returns."""
        self._closed = True
        while True:
            try:
                connection = self._pool.get_nowait()
            except queue.Empty:
                return
            try:
                connection.close()
            except Exception:
                pass

    # ------------------------------------------------------------------- pool

    def _acquire(self) -> Any:
        try:
            return self._pool.get_nowait()
        except queue.Empty:
            pass
        with self._created_lock:
            if self._created < _MAXIMUM_POOL_SIZE:
                self._created += 1
                try:
                    return self._connect()
                except Exception:
                    self._created -= 1
                    raise
        # Pool exhausted: wait for a sibling operation to return its connection, the
        # same backpressure a saturated Hikari pool applies.
        return self._pool.get(timeout=30)

    def _release(self, connection: Any) -> None:
        if self._closed:
            try:
                connection.close()
            except Exception:
                pass
            return
        self._pool.put(connection)

    def _translate(self, query: str) -> str:
        """Rewrites JDBC-style ``?`` placeholders to the driver's paramstyle. Safe as a
        plain replace: no query this layer emits ever contains a literal ``?``."""
        if self._paramstyle == "qmark":
            return query
        if self._paramstyle == "format":
            return query.replace("?", "%s")
        # "numbered" (python-oracledb): ? -> :1, :2, ...
        parts = query.split("?")
        return "".join(
            part + (f":{index + 1}" if index < len(parts) - 1 else "") for index, part in enumerate(parts)
        )

    # ------------------------------------------------------------- operations

    def execute_update(self, query: str, *objects: Any) -> None:
        """Runs a parameterized ``INSERT``/``UPDATE``/``DELETE``/DDL statement, binding
        each of ``objects`` in order."""
        try:
            connection = self._acquire()
        except Exception:
            traceback.print_exc()
            return
        try:
            cursor = connection.cursor()
            try:
                cursor.execute(self._translate(query), objects)
                connection.commit()
            finally:
                cursor.close()
        except Exception:
            traceback.print_exc()
            try:
                connection.rollback()
            except Exception:
                pass
        finally:
            self._release(connection)

    def execute_query(self, query: str, function: Callable[[Any], T], default_value: T, *objects: Any) -> T:
        """Runs a parameterized ``SELECT`` statement, binding each of ``objects`` in
        order, and maps the resulting cursor through ``function``.

        Args:
            query: The parameterized SQL query to execute.
            function: Maps the query's open cursor to the returned value; its own
                exceptions are caught and treated the same as a failed query.
            default_value: The value returned if the query fails, or if ``function``
                raises.
            objects: The values to bind, in placeholder order.
        """
        try:
            connection = self._acquire()
        except Exception:
            traceback.print_exc()
            return default_value
        try:
            cursor = connection.cursor()
            try:
                cursor.execute(self._translate(query), objects)
                try:
                    return function(cursor)
                except Exception:
                    return default_value
            finally:
                cursor.close()
                try:
                    connection.rollback()  # release any read snapshot before pooling again
                except Exception:
                    pass
        except Exception:
            traceback.print_exc()
            return default_value
        finally:
            self._release(connection)

    def execute_streaming_query(
        self, query: str, fetch_size: int, function: Callable[[Any], T], default_value: T, *objects: Any
    ) -> T:
        """Runs a parameterized ``SELECT`` like :meth:`execute_query`, but with the
        cursor's ``arraysize`` set to ``fetch_size`` so ``fetchmany``-based readers
        stream the result set from the server in bounded batches instead of buffering
        every row in memory before ``function`` sees the first one. This exists because
        for a query like "every row of a multi-gigabyte table" that buffered copy alone
        can exceed available memory, on top of whatever ``function`` builds from it -
        the same reasoning as the Java edition's fetch-size variant."""
        try:
            connection = self._acquire()
        except Exception:
            traceback.print_exc()
            return default_value
        try:
            cursor = connection.cursor()
            try:
                cursor.arraysize = fetch_size
                cursor.execute(self._translate(query), objects)
                try:
                    return function(cursor)
                except Exception:
                    return default_value
            finally:
                cursor.close()
                try:
                    connection.rollback()
                except Exception:
                    pass
        except Exception:
            traceback.print_exc()
            return default_value
        finally:
            self._release(connection)

    def execute_transaction(self, *statements: str) -> None:
        """Runs each of ``statements`` in order on a single connection, inside one
        transaction - either all of them commit or, if any one raises, none of them do.
        This exists alongside :meth:`execute_update`, which logs and swallows a failed
        statement, because some callers (e.g. installing a function/drop/create-trigger
        sequence) need "all or nothing": letting such a sequence continue past a failed
        statement would leave the schema in a state no single statement here ever
        intended. Unlike :meth:`execute_update`, this raises rather than swallowing.

        Raises:
            Exception: Whatever the driver raised; the transaction is rolled back before
                this propagates.
        """
        connection = self._acquire()
        try:
            cursor = connection.cursor()
            try:
                for sql in statements:
                    cursor.execute(sql)
                connection.commit()
            except Exception:
                connection.rollback()
                raise
            finally:
                cursor.close()
        finally:
            self._release(connection)

    # ------------------------------------------------------------------ async

    async def execute_update_async(self, query: str, *objects: Any) -> None:
        """Executes the :meth:`execute_update` process async."""
        await asyncio.to_thread(self.execute_update, query, *objects)

    async def execute_query_async(
        self, query: str, function: Callable[[Any], T], default_value: T, *objects: Any
    ) -> T:
        """Executes the :meth:`execute_query` process async."""
        return await asyncio.to_thread(self.execute_query, query, function, default_value, *objects)


def _connection_factory(
    database_type: DatabaseType, credentials: Credentials
) -> tuple[Callable[[], Any], str]:
    """Builds the vendor-specific connection factory and paramstyle for
    ``database_type`` - the counterpart of the Java edition's per-vendor JDBC URL and
    driver class selection in ``getHikariConfig``. Driver imports are deliberately local,
    so only the vendor actually connected to needs its package installed."""

    if database_type is DatabaseType.SQLITE:
        import sqlite3

        # The same file naming rule as the Java edition's "jdbc:sqlite:<fileRepository>.sqlite".
        path = f"{credentials.file_repository}.sqlite"

        def connect_sqlite() -> Any:
            # One pool serves multiple threads, so per-connection thread affinity is
            # disabled; the pool's checkout discipline provides the actual safety.
            return sqlite3.connect(path, check_same_thread=False)

        return connect_sqlite, "qmark"

    if database_type in (DatabaseType.MY_SQL, DatabaseType.MARIA_DB):
        import pymysql

        def connect_mysql() -> Any:
            return pymysql.connect(
                host=credentials.address,
                port=credentials.port,
                user=credentials.user_name,
                password=credentials.password,
                database=credentials.database,
            )

        return connect_mysql, "format"

    if database_type is DatabaseType.POSTGRES_SQL:
        import psycopg

        def connect_postgres() -> Any:
            return psycopg.connect(
                host=credentials.address,
                port=credentials.port,
                user=credentials.user_name,
                password=credentials.password,
                dbname=credentials.database,
            )

        return connect_postgres, "format"

    if database_type is DatabaseType.MICROSOFT_SQL_SERVER:
        import pymssql

        def connect_mssql() -> Any:
            return pymssql.connect(
                server=credentials.address,
                port=str(credentials.port),
                user=credentials.user_name,
                password=credentials.password,
                database=credentials.database,
            )

        return connect_mssql, "format"

    if database_type is DatabaseType.ORACLE:
        import oracledb

        def connect_oracle() -> Any:
            return oracledb.connect(
                user=credentials.user_name,
                password=credentials.password,
                dsn=f"{credentials.address}:{credentials.port}/{credentials.database}",
            )

        return connect_oracle, "numbered"

    raise ValueError(
        f"@SQLExecution: no Python connection factory for {database_type} - "
        "H2 and Apache Derby are embedded JVM databases with no Python driver"
    )
