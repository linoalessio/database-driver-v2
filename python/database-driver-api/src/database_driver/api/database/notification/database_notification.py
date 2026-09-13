"""Mirror of ``de.lino.database.database.notification.DatabaseNotification``."""

from __future__ import annotations

import threading
from abc import ABC, abstractmethod
from collections.abc import Callable

from database_driver.api.database.entity.serialized import Serialized
from database_driver.api.json.json_document import JsonDocument


class DatabaseNotification(ABC):
    """A push-based, vendor-specific notification channel: implementations watch one or
    more ``Serialized`` entity types' tables and invoke a callback the instant a row is
    written, instead of the caller polling on a timer.

    There is deliberately no vendor-agnostic implementation behind this contract - each
    backend's own change-notification primitive (e.g. Postgres ``LISTEN``/``NOTIFY``)
    differs too much to unify, so every implementation lives in the plugin module under
    its own vendor package.
    """

    @abstractmethod
    def get_channel(self) -> str:
        """Returns the backend-specific channel/topic this instance watches for
        notifications on."""

    @abstractmethod
    def is_running(self) -> bool:
        """Returns ``True`` between a successful :meth:`start` call and the matching
        :meth:`shutdown`."""

    @abstractmethod
    def get_thread(self) -> threading.Thread:
        """Returns the daemon thread :meth:`start` spawned to block on incoming
        notifications.

        Raises:
            RuntimeError: If :meth:`start` has not been called yet.
        """

    @abstractmethod
    def watch(self, *types: type[Serialized]) -> None:
        """Installs whatever backend-specific trigger or subscription is needed so future
        writes to each given entity type's table raise a notification on
        :meth:`get_channel`."""

    @abstractmethod
    def start(self, on_notification: Callable[[JsonDocument], None]) -> None:
        """Starts listening for notifications on :meth:`get_channel`, invoking
        ``on_notification`` once per notification received, in the order received. A
        no-op if already running."""

    @abstractmethod
    def shutdown(self) -> None:
        """Stops listening and releases whatever resources :meth:`start` acquired. A
        no-op if :meth:`start` was never called."""
