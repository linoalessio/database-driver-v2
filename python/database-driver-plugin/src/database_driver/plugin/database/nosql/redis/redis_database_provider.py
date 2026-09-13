"""Mirror of ``de.lino.database.database.nosql.redis.RedisDatabaseProvider``."""

from __future__ import annotations

import threading
from collections.abc import Callable
from typing import Any

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.notification.redis_counter_service import RedisCounterService
from database_driver.api.database.section_config import SectionConfig

from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.redis.redis_database_section import RedisDatabaseSection

_SCAN_BATCH_SIZE = 100
"""How many keys each ``SCAN`` round trip asks the server for - the historical batch
size, bounding a scan's per-round-trip work without starving it."""


class RedisDatabaseProvider(AbstractLazyDatabaseProvider):
    """The ``DatabaseProvider`` backed by a Redis database, each section a ``"<name>:*"``
    key prefix via ``RedisDatabaseSection``, all sharing this database's single pooled
    redis-py client. Section lifecycle and caching live in
    ``AbstractLazyDatabaseProvider``; this class only supplies the keyspace-level
    storage operations - discovering prefixes, constructing a section, wiping a prefix.
    redis-py's ``Redis`` client is itself pool-backed and thread-safe, so every method
    here is safe to call concurrently without additional locking.

    Discovers every existing key prefix as a section name from a single keyspace
    ``SCAN`` - O(keys) once. (The historical Java implementation once created one
    section *per key*, each running its own full-keyspace scan - O(keys²) work; both are
    long gone with prefix discovery, here as there.)
    """

    def __init__(self, credentials: Credentials) -> None:
        super().__init__()

        import redis

        if not credentials.user_name and not credentials.password:
            self.client: Any = redis.Redis(
                host=credentials.address,
                port=credentials.port,
                db=int(credentials.database),
                max_connections=50,
            )
        else:
            self.client = redis.Redis.from_url(
                f"redis://:{credentials.password}@{credentials.address}:{credentials.port}/{credentials.database}",
                max_connections=50,
            )

        # Lazily constructed by counter_service(); double-checked so concurrent
        # first-callers all end up sharing the exact same instance, not one each.
        self._counter_service: RedisCounterService | None = None
        self._counter_lock = threading.Lock()

        self.reload()

    def shutdown(self) -> None:
        self.client.close()
        self.forget_sections()

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """A single cursor-based ``SCAN`` over the whole keyspace, reducing each key
        ``"<prefix>:<id>"`` to its prefix (a key without a ``':'`` passes through whole,
        mirroring how ``RedisDatabaseSection`` would name it). Duplicates collapse in the
        caller's name set, so N keys cost one O(N) pass."""
        for key in self.client.scan_iter(match="*", count=_SCAN_BATCH_SIZE):
            key_text = key.decode("utf-8") if isinstance(key, bytes) else key
            separator = key_text.find(":")
            consumer(key_text if separator < 0 else key_text[:separator])

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return RedisDatabaseSection(self.client, name, config)

    def drop_section_remote(self, name: str) -> None:
        """A cursor-based ``SCAN`` over ``"<name>:*"`` with one ``DEL`` per scanned batch
        rather than one per key. The pattern deliberately includes the ``':'`` separator
        so deleting section ``"users"`` can never take keys of an unrelated section that
        merely shares the character prefix (like ``"users2"``) with it."""
        batch: list[Any] = []
        for key in self.client.scan_iter(match=f"{name}:*", count=_SCAN_BATCH_SIZE):
            batch.append(key)
            if len(batch) >= _SCAN_BATCH_SIZE:
                self.client.delete(*batch)
                batch.clear()
        if batch:
            self.client.delete(*batch)

    def counter_service(self) -> RedisCounterService:
        """Returns this provider's ``RedisCounterService``, constructing it on first call
        and reusing that same instance afterward. The returned service shares this
        provider's own client (and so its connection pool) with every section this
        provider manages, rather than opening a second, independent pool against the
        same Redis instance."""
        service = self._counter_service
        if service is not None:
            return service

        with self._counter_lock:
            service = self._counter_service
            if service is None:
                from database_driver.plugin.database.nosql.redis.redis_py_counter_service import (
                    RedisPyCounterService,
                )

                service = RedisPyCounterService(self.client)
                self._counter_service = service

        return service
