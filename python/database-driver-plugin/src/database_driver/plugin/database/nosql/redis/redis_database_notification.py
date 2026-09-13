"""Mirror of ``de.lino.database.database.nosql.redis.RedisDatabaseNotification``."""

from __future__ import annotations

import threading
import traceback
from collections.abc import Callable
from typing import Any, Optional

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.entity.serialized import Serialized
from database_driver.api.database.notification.database_notification import DatabaseNotification
from database_driver.api.json.json_document import JsonDocument


class RedisDatabaseNotification(DatabaseNotification):
    """The Redis implementation of ``DatabaseNotification``, mirroring
    ``PostgresDatabaseNotification``'s contract shape on top of Redis Pub/Sub instead of
    Postgres ``LISTEN``/``NOTIFY``.

    **:meth:`watch` is a documented no-op.** Redis has no trigger concept to install,
    unlike Postgres where ``watch`` creates an ``AFTER INSERT OR UPDATE`` trigger per
    table. Instead, ``RedisDatabaseSection``'s writes themselves unconditionally
    ``PUBLISH`` a ``{"table", "operation", "id"}`` notification - the exact same payload
    shape ``PostgresDatabaseNotification``'s trigger function emits - on
    ``redis_database_section.CHANGE_NOTIFICATION_CHANNEL`` every time a row is written,
    by any writer sharing that Redis instance. There is nothing left for ``watch`` to
    install; this instance only actually observes those publishes once its channel
    equals that channel constant exactly - any other channel is valid but will silently
    never receive anything from ``RedisDatabaseSection``'s own writes.

    **One dedicated, non-pooled connection, one daemon thread** - the same shape
    ``PostgresDatabaseNotification.start`` uses for its raw ``LISTEN`` connection. A
    blocking Pub/Sub subscription cannot run through a shared connection pool the way
    the sections' own reads/writes do - a connection borrowed for the lifetime of a
    blocking subscribe would never be returned, silently starving every other borrower.
    :meth:`start` instead opens its own single client built directly from the
    credentials, independent of any ``RedisDatabaseProvider``.

    The callback passed to :meth:`start` is wrapped in its own try/except (log, don't
    propagate) - an uncaught exception escaping the message handler would otherwise kill
    the subscription with no reconnect logic.

    :meth:`start`/:meth:`shutdown` are mutually exclusive, guarded by one lifecycle
    lock: without it, two threads racing ``start`` could each pass the "already running"
    check before either assigns the listener thread, leaking a connection and a thread
    ``shutdown`` would no longer have a reference to.
    """

    def __init__(self, credentials: Credentials, channel: str) -> None:
        """Args:
            credentials: The Redis connection details :meth:`start` opens its dedicated
                subscriber connection with.
            channel: The Redis Pub/Sub channel to subscribe on; pass
                ``redis_database_section.CHANGE_NOTIFICATION_CHANNEL`` to actually
                observe ``RedisDatabaseSection``'s own write notifications.

        Raises:
            TypeError: If either argument is ``None``.
        """
        if credentials is None:
            raise TypeError("@RedisDatabaseNotification.init: credentials cannot be None")
        if channel is None:
            raise TypeError("@RedisDatabaseNotification.init: channel cannot be None")

        self._credentials = credentials
        self._channel = channel

        self._lifecycle_lock = threading.Lock()
        self._subscriber_connection: Any = None
        self._pubsub: Any = None
        self._running = False
        self._listener_thread: Optional[threading.Thread] = None

    def watch(self, *types: type[Serialized]) -> None:
        """A documented no-op - see the class docstring for why. Redis has no trigger
        concept to install per type/table the way the Postgres implementation does;
        every write already publishes unconditionally, regardless of which types are
        passed here."""

    def start(self, on_notification: Callable[[JsonDocument], None]) -> None:
        """Opens this instance's dedicated subscriber connection and starts a daemon
        thread that blocks on the Pub/Sub socket - a real blocking read, not a
        sleep-and-poll loop - invoking ``on_notification`` once per message received, in
        the order received. Calling this again while already running is a no-op."""
        if on_notification is None:
            raise TypeError("@RedisDatabaseNotification.start: on_notification cannot be None")

        with self._lifecycle_lock:
            if self._listener_thread is not None:
                return

            self._subscriber_connection = self._open_connection()
            self._pubsub = self._subscriber_connection.pubsub(ignore_subscribe_messages=True)
            self._pubsub.subscribe(self._channel)

            self._running = True
            self._listener_thread = threading.Thread(
                target=self._listen,
                args=(on_notification,),
                name=f"{self._channel}-redis-change-notifier",
                daemon=True,
            )
            self._listener_thread.start()

    def _open_connection(self) -> Any:
        import redis

        if not self._credentials.user_name and not self._credentials.password:
            client = redis.Redis(
                host=self._credentials.address,
                port=self._credentials.port,
                db=int(self._credentials.database),
            )
        else:
            client = redis.Redis.from_url(
                f"redis://:{self._credentials.password}@{self._credentials.address}"
                f":{self._credentials.port}/{self._credentials.database}"
            )
        return client

    def _listen(self, on_notification: Callable[[JsonDocument], None]) -> None:
        try:
            # Blocks on the Pub/Sub socket until shutdown() closes it - never busy-waits.
            for message in self._pubsub.listen():
                if not self._running:
                    return
                if message.get("type") != "message":
                    continue
                try:
                    payload = message["data"]
                    if isinstance(payload, bytes):
                        payload = payload.decode("utf-8")
                    on_notification(JsonDocument(payload))
                except Exception:
                    # log, don't propagate - an uncaught exception here would kill the
                    # subscription with no reconnect logic, see class docstring
                    traceback.print_exc()
        except Exception:
            if not self._running:
                return  # shutdown() closed the connection on purpose
            traceback.print_exc()

    def shutdown(self) -> None:
        """Stops the listener thread and closes its dedicated subscriber connection, but
        leaves the credentials untouched, since this instance does not own their
        lifecycle. Waits up to 2 seconds for the listener thread to actually exit.
        Idempotent - safe to call more than once, safe to call before :meth:`start`.
        Once this returns, :meth:`start` can be called again to open a fresh listener."""
        with self._lifecycle_lock:
            self._running = False

            if self._pubsub is not None:
                try:
                    self._pubsub.unsubscribe()
                    self._pubsub.close()
                except Exception:
                    pass  # an already-broken subscription is not an error condition here

            if self._subscriber_connection is not None:
                try:
                    self._subscriber_connection.close()
                except Exception:
                    pass

            thread = self._listener_thread
            if thread is None:
                return

            thread.join(timeout=2.0)

            self._listener_thread = None
            self._pubsub = None
            self._subscriber_connection = None

    def get_channel(self) -> str:
        return self._channel

    def is_running(self) -> bool:
        return self._running

    def get_thread(self) -> threading.Thread:
        thread = self._listener_thread
        if thread is None:
            raise RuntimeError("@RedisDatabaseNotification.get_thread: start() has not been called yet")
        return thread
