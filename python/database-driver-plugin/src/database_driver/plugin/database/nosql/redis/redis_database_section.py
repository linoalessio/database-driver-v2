"""Mirror of ``de.lino.database.database.nosql.redis.RedisDatabaseSection``."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.json_document import JsonDocument

from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection

CHANGE_NOTIFICATION_CHANNEL = "database-driver-changes"
"""The Redis Pub/Sub channel every ``persist_insert``/``persist_update`` call
unconditionally ``PUBLISH``es a change notification to, in the exact
``{"table", "operation", "id"}`` JSON shape ``PostgresDatabaseNotification``'s own
trigger function emits, so a consumer needs no special-casing between the two backends.
This is one fixed channel shared by every ``RedisDatabaseSection`` on a given Redis
instance, not a per-section or per-provider setting - unlike Postgres, which binds an
arbitrary, caller-chosen channel to each table via its own trigger, Redis has no
server-side trigger concept to bind a channel to a key prefix with, so there is nothing
to make this configurable per instance. A ``RedisDatabaseNotification`` must be
constructed with this exact channel name to observe these publishes."""

_SCAN_BATCH_SIZE = 100
"""How many keys each ``SCAN`` round trip asks the server for, in every cursor-based
primitive here - the historical batch size, bounding a scan's per-round-trip work
without starving it."""


class RedisDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one Redis key prefix (``"<name>:<id>"`` per
    entry). All caching lives in ``AbstractCachedDatabaseSection``; this class only
    supplies the key prefix's storage primitives - point primitives as single-key
    commands, whole-section primitives as cursor-based ``SCAN`` passes so no primitive
    ever blocks the server the way a ``KEYS`` call would.

    Prepares the section without touching Redis at all - a key prefix needs no
    server-side container. Constructing without an explicit ``config`` mirrors the
    historical Java constructor: ``FULL`` and warmed immediately.
    """

    def __init__(self, client: Any, name: str, config: SectionConfig | None = None) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        # The redis-py client shared with this section's owning provider and every one
        # of its sibling sections; redis.Redis is itself pool-backed and thread-safe.
        self.client = client

        if direct:
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """A cursor-based ``SCAN`` over ``"<name>:*"`` with a per-key ``GET``, in bounded
        batches."""
        prefix = f"{self.get_name()}:"
        for key in self.client.scan_iter(match=f"{prefix}*", count=_SCAN_BATCH_SIZE):
            key_text = key.decode("utf-8") if isinstance(key, bytes) else key
            data = self.client.get(key)
            if data is None:
                raise NoSuchDataFound(key_text)
            consumer(DatabaseEntry(key_text.replace(prefix, ""), JsonDocument(data)))

    def fetch_one(self, id: str) -> DatabaseEntry | None:
        """A single ``GET`` on the entry's full key - Redis' native point read."""
        data = self.client.get(self._entry_key(id))
        return None if data is None else DatabaseEntry(id, JsonDocument(data))

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        # database_entry.document is already the full "data"-enveloped document;
        # appending it as-is under another "data" key would double-wrap it, so its
        # already-unwrapped get_meta_data() is used instead, matching persist_update.
        self.client.set(
            self._entry_key(database_entry.id),
            JsonDocument().append("data", database_entry.get_meta_data()).to_bytes(),
        )
        self._publish_change_notification("INSERT", database_entry.id)

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        self.client.set(
            self._entry_key(database_entry.id),
            JsonDocument().append("data", database_entry.get_meta_data()).to_bytes(),
        )
        self._publish_change_notification("UPDATE", database_entry.id)

    def persist_delete(self, id: str) -> None:
        self.client.delete(self._entry_key(id))

    def count_remote(self) -> int:
        """A full cursor-based ``SCAN`` over ``"<name>:*"``, counting matches - Redis
        keeps no per-prefix key count, so this is O(keyspace) per call and a full
        in-memory mode answers ``count()`` far cheaper for hot sections."""
        return sum(1 for _ in self.client.scan_iter(match=f"{self.get_name()}:*", count=_SCAN_BATCH_SIZE))

    def exists_remote(self, id: str) -> bool:
        """A single ``EXISTS`` on the entry's full key - Redis' native point check."""
        return bool(self.client.exists(self._entry_key(id)))

    def clear_remote(self) -> None:
        """A cursor-based ``SCAN`` over ``"<name>:*"`` with one ``DEL`` per scanned batch
        rather than one per key, cutting round trips from O(matched keys) to
        O(matched keys / batch size)."""
        batch: list[Any] = []
        for key in self.client.scan_iter(match=f"{self.get_name()}:*", count=_SCAN_BATCH_SIZE):
            batch.append(key)
            if len(batch) >= _SCAN_BATCH_SIZE:
                self.client.delete(*batch)
                batch.clear()
        if batch:
            self.client.delete(*batch)

    def _entry_key(self, id: str) -> str:
        """Builds the full Redis key an entry with ``id`` is stored under - the single
        naming rule (``"<name>:<id>"``) every primitive above shares."""
        return f"{self.get_name()}:{id}"

    def _publish_change_notification(self, operation: str, id: str) -> None:
        """``PUBLISH``es a ``{"table", "operation", "id"}`` change notification on
        :data:`CHANGE_NOTIFICATION_CHANNEL`. Unconditional - ``PUBLISH`` to a channel
        with zero subscribers is a cheap, single round trip in Redis, so this runs on
        every write with no "is anyone listening" gate."""
        payload = (
            JsonDocument()
            .append("table", self.get_name())
            .append("operation", operation)
            .append("id", id)
            .to_json()
        )
        self.client.publish(CHANGE_NOTIFICATION_CHANNEL, payload)
