"""Mirror of ``de.lino.database.database.sql.postgresql.PostgresDatabaseNotification``."""

from __future__ import annotations

import re
import threading
import traceback
from collections.abc import Callable
from typing import Any, Optional

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType
from database_driver.api.database.entity.serialized import Serialized
from database_driver.api.database.notification.database_notification import DatabaseNotification
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.sql.sql_execution import SQLExecution

_SAFE_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class PostgresDatabaseNotification(DatabaseNotification):
    """Blocks a dedicated daemon thread on Postgres ``LISTEN``/``NOTIFY`` so a caller
    learns about a row written to a watched table the instant it happens - true push,
    not a poll loop on a timer. The Postgres implementation of ``DatabaseNotification``.

    **Two connections, for two different reasons.** :meth:`watch` - trigger
    installation, a plain call-and-return DDL statement - runs through ``SQLExecution``,
    the same pooled connection helper the SQL provider/sections run every query through.
    ``LISTEN``/``NOTIFY`` cannot go through it, though: :meth:`start` needs to hold one
    specific connection open and block a read on it indefinitely, and ``SQLExecution``
    has no API for that - every method it exposes borrows a connection from its pool and
    returns it before the call is done. So :meth:`start` instead opens its own single,
    dedicated psycopg connection (not pooled, not shared) and issues ``LISTEN`` on it
    directly.

    **This only catches writes that reach the watched table as a real SQL
    ``INSERT``/``UPDATE``** - which every writer talking to that table causes, regardless
    of process, satisfying "notify me about writes from any source", not just this
    process' own calls.

    **Fragile by construction - read before relying on this in production.** The trigger
    installed by :meth:`watch` assumes the exact schema ``SQLDatabaseSection`` creates
    for every section (``id TEXT, data BYTEA``; table name = the entity type's class
    name). Re-verify whenever that schema changes.

    :meth:`watch` must be called once per table, after that table already exists; a
    brand-new entity type first persisted after :meth:`start` has already been called is
    not picked up automatically - call :meth:`watch` again for it. Its three DDL
    statements install as one transaction via ``SQLExecution.execute_transaction`` - the
    function, the trigger drop, and the trigger create either all apply or none do, so a
    mid-sequence failure can never leave a table with a half-updated or missing trigger.

    :meth:`start`/:meth:`shutdown` are mutually exclusive, guarded by one lifecycle lock:
    without it, two threads racing :meth:`start` could each pass the "already running"
    check before either assigns the listener thread, leaking a connection and a thread
    :meth:`shutdown` would no longer have a reference to.
    """

    def __init__(self, credentials: Credentials, channel: str) -> None:
        """Args:
            credentials: The Postgres connection details, used both to build the pooled
                ``SQLExecution`` and to open :meth:`start`'s dedicated ``LISTEN``
                connection.
            channel: The Postgres notification channel to listen on.

        Raises:
            TypeError: If any argument is ``None``.
            ValueError: If ``channel`` is not a safe, unquoted SQL identifier.
        """
        if credentials is None:
            raise TypeError("@PostgresDatabaseNotification.init: credentials cannot be None")
        if channel is None:
            raise TypeError("@PostgresDatabaseNotification.init: channel cannot be None")
        if not _SAFE_IDENTIFIER.fullmatch(channel):
            raise ValueError(f"@PostgresDatabaseNotification.init: '{channel}' is not a safe SQL identifier")

        self._sql_execution = SQLExecution(DatabaseType.POSTGRES_SQL, credentials)
        self._credentials = credentials
        self._channel = channel

        self._lifecycle_lock = threading.Lock()
        self._listen_connection: Any = None
        self._running = False
        self._listener_thread: Optional[threading.Thread] = None

    def watch(self, *types: type[Serialized]) -> None:
        """Installs an idempotent ``AFTER INSERT OR UPDATE`` trigger on each given entity
        type's table (name = the class name) that ``pg_notify``s this instance's channel
        with a small JSON payload - ``{"table": ..., "operation": ..., "id": ...}``,
        never the row's own data - every time a row is written, by any writer."""
        for entity_type in types:
            self._install_trigger(entity_type.__name__)

    def _install_trigger(self, table: str) -> None:
        if not _SAFE_IDENTIFIER.fullmatch(table):
            raise ValueError(f"@PostgresDatabaseNotification.watch: '{table}' is not a safe SQL identifier")

        function = f"{self._channel}_notify_fn_{table}"
        trigger = f"{self._channel}_notify_trg_{table}"

        create_function = f"""
            CREATE OR REPLACE FUNCTION {function}() RETURNS trigger AS $body$
            BEGIN
                PERFORM pg_notify('{self._channel}', json_build_object('table', TG_TABLE_NAME, 'operation', TG_OP, 'id', NEW.id)::text);
                RETURN NEW;
            END;
            $body$ LANGUAGE plpgsql;
        """
        drop_trigger = f"DROP TRIGGER IF EXISTS {trigger} ON {table};"
        create_trigger = f"""
            CREATE TRIGGER {trigger}
            AFTER INSERT OR UPDATE ON {table}
            FOR EACH ROW EXECUTE FUNCTION {function}();
        """

        try:
            self._sql_execution.execute_transaction(create_function, drop_trigger, create_trigger)
        except Exception:
            # Same log-and-continue convention execute_update uses elsewhere - but here
            # the transaction guarantees the table is left with either its old trigger
            # fully intact or its new one fully installed, never a half-applied mix.
            traceback.print_exc()

    def start(self, on_notification: Callable[[JsonDocument], None]) -> None:
        """Opens this instance's dedicated ``LISTEN`` connection and starts a daemon
        thread that blocks on the connection's notification generator - a real blocking
        socket read, not a sleep-and-poll loop - invoking ``on_notification`` once per
        notification, in the order received. Calling this again while already running is
        a no-op."""
        if on_notification is None:
            raise TypeError("@PostgresDatabaseNotification.start: on_notification cannot be None")

        with self._lifecycle_lock:
            if self._listener_thread is not None:
                return

            import psycopg

            try:
                self._listen_connection = psycopg.connect(
                    host=self._credentials.address,
                    port=self._credentials.port,
                    user=self._credentials.user_name,
                    password=self._credentials.password,
                    dbname=self._credentials.database,
                    autocommit=True,
                )
                self._listen_connection.execute(f"LISTEN {self._channel};")
            except Exception as failure:
                raise RuntimeError(
                    f"@PostgresDatabaseNotification.start: failed to open the LISTEN connection on channel '{self._channel}'"
                ) from failure

            self._running = True
            self._listener_thread = threading.Thread(
                target=self._listen,
                args=(on_notification,),
                name=f"{self._channel}-postgres-change-notifier",
                daemon=True,
            )
            self._listener_thread.start()

    def _listen(self, on_notification: Callable[[JsonDocument], None]) -> None:
        try:
            # psycopg's notifies() generator blocks on the socket until a notification
            # arrives or the connection is closed by shutdown() - never busy-waits.
            for notification in self._listen_connection.notifies():
                if not self._running:
                    return
                on_notification(JsonDocument(notification.payload))
        except Exception:
            if not self._running:
                return  # shutdown() closed the connection on purpose
            traceback.print_exc()
            # connection is broken; no reconnect logic here, see class docstring

    def shutdown(self) -> None:
        """Stops the listener thread and closes its dedicated ``LISTEN`` connection -
        which also unblocks its in-progress notification read - but leaves the pooled
        ``SQLExecution`` untouched, since this instance does not own its lifecycle. Waits
        up to 2 seconds for the listener thread to actually exit. A no-op if
        :meth:`start` was never called; once this returns, :meth:`start` can be called
        again to open a fresh listener."""
        with self._lifecycle_lock:
            self._running = False

            if self._listen_connection is not None:
                try:
                    self._listen_connection.close()
                except Exception:
                    pass  # closing an already-broken connection is not an error here

            thread = self._listener_thread
            if thread is None:
                return

            thread.join(timeout=2.0)

            self._listener_thread = None
            self._listen_connection = None

    def get_channel(self) -> str:
        return self._channel

    def is_running(self) -> bool:
        return self._running

    def get_thread(self) -> threading.Thread:
        thread = self._listener_thread
        if thread is None:
            raise RuntimeError("@PostgresDatabaseNotification.get_thread: start() has not been called yet")
        return thread
