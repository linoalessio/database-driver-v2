"""Mirror of ``de.lino.database.DatabaseRepositoryRegistry``."""

from __future__ import annotations

import asyncio
import threading
import traceback
import weakref
from typing import Any, ClassVar

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_provider import DatabaseProvider
from database_driver.api.database.database_type import DatabaseType
from database_driver.api.database_repository import DatabaseRepository
from database_driver.api.json.json_document import JsonDocument
from database_driver.api.utils.cache.cache import Cache
from database_driver.api.utils.pair import Pair

from database_driver.plugin.database.file.default_file_provider import DefaultFileProvider


class DatabaseRepositoryRegistry(DatabaseRepository):
    """The concrete, process-wide ``DatabaseRepository``: tracks every registered
    ``DatabaseProvider`` by numeric id, and knows how to construct one for each supported
    ``DatabaseType``. Installs itself as ``DatabaseRepository``'s singleton accessor on
    construction, and as a side effect of constructing a ``DefaultFileProvider``, also
    installs the default ``FileProvider`` singleton.

    Every id-keyed mutation (:meth:`register_database_provider`,
    :meth:`unregister_database_provider`) runs under one lock rather than as a separate
    check-then-act pair, so concurrent calls for the same id cannot race each other into
    double-registering or double-shutting-down a database - the Python analogue of the
    Java edition's atomic ``ConcurrentHashMap`` operations.
    """

    # Whether log_bytes prints anything; set once from the constructor but read from
    # arbitrary threads via every section implementation.
    _LOG_BYTES: ClassVar[bool] = False

    # How often the TTL sweep runs, in seconds. Cache.evict_expired() is an O(n) full
    # scan by contract, so it must run on a timer rather than on the hot path - and 60
    # seconds keeps a TTL-expired entry's worst-case extra lifetime in the same order of
    # magnitude as typical TTLs while making the scan's cost negligible.
    _TTL_SWEEP_PERIOD_SECONDS: ClassVar[float] = 60.0

    # Every TTL-bearing Cache currently subject to periodic evict_expired() sweeps,
    # registered by cache-mode-configured database sections. Weakly keyed on purpose: a
    # section replaced under a new configuration (or discarded with its provider) must
    # not keep its abandoned cache - and the entries that cache pins - alive through this
    # registry; once nothing else references the cache it simply drops out of the sweep.
    _TTL_SWEPT_CACHES: ClassVar[weakref.WeakKeyDictionary] = weakref.WeakKeyDictionary()
    _TTL_SWEPT_LOCK: ClassVar[threading.Lock] = threading.Lock()

    # The single shared daemon thread running the sweep; created lazily when the first
    # TTL-configured cache appears - a process that never configures a TTL never pays for
    # the thread - and stopped again by shutdown()/shutdown_async(). Daemon so a consumer
    # that forgets to shut the repository down is not kept alive by cache housekeeping.
    _ttl_sweeper: ClassVar[threading.Thread | None] = None
    _ttl_sweeper_stop: ClassVar[threading.Event | None] = None
    _SWEEPER_LOCK: ClassVar[threading.Lock] = threading.Lock()

    def __init__(self, log_bytes: bool = False) -> None:
        """Installs this instance as ``DatabaseRepository``'s singleton and, as a side
        effect of constructing a ``DefaultFileProvider``, installs the ``FileProvider``
        singleton too - ``FileProvider``'s own ``set_instance`` is reserved for its own
        subclasses, so this indirection is the only way to trigger it from here.

        Args:
            log_bytes: Whether :meth:`log_bytes` should print anything.
        """
        DatabaseRepository.set_instance(self)
        DatabaseRepositoryRegistry._LOG_BYTES = log_bytes

        # Every registered database, keyed by its caller-assigned id, each entry pairing
        # the database with the DatabaseType it was created for.
        self._database_providers: dict[int, Pair[DatabaseType, DatabaseProvider]] = {}
        self._providers_lock = threading.Lock()

        DefaultFileProvider()

    # -------------------------------------------------------------- TTL sweeps

    @classmethod
    def schedule_ttl_sweeps(cls, cache: Cache[Any, Any]) -> None:
        """Registers ``cache`` for periodic ``evict_expired()`` sweeps, starting the
        shared sweeper thread if this is the first TTL-bearing cache of the process.
        Called by the caching engine for every section configured with a TTL; without
        this, an expired entry would only ever leave memory when its own key happens to
        be touched again, letting a bounded-but-idle section pin expired data
        indefinitely.

        Args:
            cache: The cache to sweep periodically; held weakly, so registration never
                extends the cache's lifetime.
        """
        with cls._TTL_SWEPT_LOCK:
            cls._TTL_SWEPT_CACHES[cache] = True

        if cls._ttl_sweeper is not None:
            return

        with cls._SWEEPER_LOCK:
            if cls._ttl_sweeper is None:
                stop = threading.Event()
                sweeper = threading.Thread(
                    target=cls._run_sweeper, args=(stop,), name="database-driver-ttl-sweeper", daemon=True
                )
                cls._ttl_sweeper_stop = stop
                cls._ttl_sweeper = sweeper
                sweeper.start()

    @classmethod
    def _run_sweeper(cls, stop: threading.Event) -> None:
        while not stop.wait(cls._TTL_SWEEP_PERIOD_SECONDS):
            cls._sweep_expired_cache_entries()

    @classmethod
    def _sweep_expired_cache_entries(cls) -> None:
        """One sweep pass: ``evict_expired()`` on every registered cache, each guarded so
        one misbehaving cache cannot kill the shared sweeper thread."""
        with cls._TTL_SWEPT_LOCK:
            snapshot = list(cls._TTL_SWEPT_CACHES.keys())
        for cache in snapshot:
            try:
                cache.evict_expired()
            except Exception:
                traceback.print_exc()

    @classmethod
    def _stop_ttl_sweeper(cls) -> None:
        """Stops the shared TTL sweeper and forgets every registered cache, as part of
        shutting the repository down. Safe against a later revival: if a new
        TTL-configured section appears after this, :meth:`schedule_ttl_sweeps` simply
        starts a fresh sweeper."""
        with cls._SWEEPER_LOCK:
            stop = cls._ttl_sweeper_stop
            cls._ttl_sweeper = None
            cls._ttl_sweeper_stop = None
        if stop is not None:
            stop.set()
        with cls._TTL_SWEPT_LOCK:
            cls._TTL_SWEPT_CACHES.clear()

    @classmethod
    def log_bytes(cls, message: str, document: JsonDocument) -> None:
        """Prints ``document``'s serialized size in bytes under ``message``, if byte
        logging is enabled; a no-op otherwise, checked before ``document`` is ever
        serialized so disabled logging costs nothing beyond the flag check.

        Args:
            message: A ``%d``-format string describing what is being measured.
            document: The document whose serialized size is measured.
        """
        if not cls._LOG_BYTES:
            return
        print(message % len(document.to_bytes()))

    # ------------------------------------------------------ DatabaseRepository

    def get_database_provider_pool(self, database_type: DatabaseType | None = None) -> list[DatabaseProvider]:
        pairs = list(self._database_providers.values())
        if database_type is None:
            return [pair.second for pair in pairs]
        return [pair.second for pair in pairs if pair.first is database_type]

    def shutdown(self) -> None:
        for pair in list(self._database_providers.values()):
            try:
                pair.second.shutdown()
            except Exception:
                traceback.print_exc()
        self._database_providers.clear()
        DatabaseRepositoryRegistry._stop_ttl_sweeper()

    async def shutdown_async(self) -> None:
        """Shuts down every registered database concurrently rather than one at a time,
        since each database owns its own, independent connection and shutting one down is
        typically I/O-bound - overriding ``DatabaseRepository``'s default, which would
        otherwise just run the whole sequential :meth:`shutdown` on a single worker
        thread."""
        pending = [pair.second.shutdown_async() for pair in self._database_providers.values()]
        await asyncio.gather(*pending, return_exceptions=True)
        self._database_providers.clear()
        DatabaseRepositoryRegistry._stop_ttl_sweeper()

    def convert(self, source_id: int, target_id: int) -> Pair[DatabaseProvider, DatabaseProvider]:
        source_pair = self._database_providers.get(source_id)
        if source_pair is None:
            raise RuntimeError(
                f"@DatabaseRepositoryRegistry.convert: Database Provider with id #{source_id} does not exist"
            )

        target_pair = self._database_providers.get(target_id)
        if target_pair is None:
            raise RuntimeError(
                f"@DatabaseRepositoryRegistry.convert: Database Provider with id #{target_id} does not exist"
            )

        source_type = source_pair.first
        source = source_pair.second
        destination = target_pair.second

        for section in source.get_sections():
            if source_type is DatabaseType.REDIS:
                # Redis section names are key prefixes; the destination gets the prefix
                # itself as its section name.
                section_name = section.get_name().split(":")[0]
                database_section = destination.create_section(section_name)
                for entry in section.get_entries():
                    database_section.insert(entry)
            else:
                if destination.exists_section(section.get_name()):
                    destination.delete_section(section.get_name())
                database_section = destination.create_section(section.get_name())
                for entry in section.get_entries():
                    database_section.insert(entry)

        return Pair(source, destination)

    def find_database_provider_by_id(self, id: int) -> DatabaseProvider | None:
        pair = self._database_providers.get(id)
        return None if pair is None else pair.second

    def register_database_provider(
        self, id: int, database_type: DatabaseType, credentials: Credentials
    ) -> DatabaseProvider:
        with self._providers_lock:
            if id in self._database_providers:
                raise RuntimeError(
                    f"@DatabaseRepositoryRegistry.register_database_provider: Provider with id #{id} already exists"
                )
            pair = Pair(database_type, _create_provider(database_type, credentials))
            self._database_providers[id] = pair
        return pair.second

    def unregister_database_provider(self, id: int) -> DatabaseProvider:
        with self._providers_lock:
            pair = self._database_providers.pop(id, None)
        if pair is None:
            raise RuntimeError(
                f"@DatabaseRepositoryRegistry.unregister_database_provider: "
                f"Database Provider with id #{id} does not exist"
            )
        unregistered = pair.second
        unregistered.shutdown()
        return unregistered


def _create_provider(database_type: DatabaseType, credentials: Credentials) -> DatabaseProvider:
    """Constructs a fresh ``DatabaseProvider`` for ``database_type``, connected with
    ``credentials``. Extracted out of ``register_database_provider`` so the lock-held
    region stays short - the same reasoning as the Java edition keeping its
    ``Map#compute`` remapping function to one call.

    Backend modules import lazily, so a consumer that never registers, say, a MongoDB
    provider never needs ``pymongo`` installed - the extras-based counterpart of the Java
    module bundling every driver jar. ``H2_DB`` and ``APACHE_DERBY`` are embedded JVM
    databases with no Python driver and are rejected here; see ``DatabaseType``.
    """
    if database_type is DatabaseType.MY_SQL:
        from database_driver.plugin.database.sql.mysql.mysql_database_provider import MySQLDatabaseProvider

        return MySQLDatabaseProvider(credentials)
    if database_type is DatabaseType.MARIA_DB:
        from database_driver.plugin.database.sql.mariadb.mariadb_database_provider import MariaDBDatabaseProvider

        return MariaDBDatabaseProvider(credentials)
    if database_type is DatabaseType.POSTGRES_SQL:
        from database_driver.plugin.database.sql.postgresql.postgresql_database_provider import (
            PostgreSQLDatabaseProvider,
        )

        return PostgreSQLDatabaseProvider(credentials)
    if database_type is DatabaseType.ORACLE:
        from database_driver.plugin.database.sql.orcale.oracle_sql_database_provider import (
            OracleSQLDatabaseProvider,
        )

        return OracleSQLDatabaseProvider(credentials)
    if database_type is DatabaseType.MICROSOFT_SQL_SERVER:
        from database_driver.plugin.database.sql.microsoft.microsoft_sql_server_database_provider import (
            MicrosoftSQLServerDatabaseProvider,
        )

        return MicrosoftSQLServerDatabaseProvider(credentials)
    if database_type is DatabaseType.APACHE_DERBY:
        from database_driver.plugin.database.sql.derby.apache_derby_database_provider import (
            ApacheDerbyDatabaseProvider,
        )

        return ApacheDerbyDatabaseProvider(credentials)  # type: ignore[return-value]  # always raises
    if database_type is DatabaseType.SQLITE:
        from database_driver.plugin.database.sql.sqlite.sqlite_database_provider import SQLiteDatabaseProvider

        return SQLiteDatabaseProvider(credentials)
    if database_type is DatabaseType.H2_DB:
        from database_driver.plugin.database.sql.h2db.h2_database_provider import H2DatabaseProvider

        return H2DatabaseProvider(credentials)  # type: ignore[return-value]  # always raises
    if database_type is DatabaseType.MONGO_DB:
        from database_driver.plugin.database.nosql.mongodb.mongodb_database_provider import (
            MongoDBDatabaseProvider,
        )

        return MongoDBDatabaseProvider(credentials)
    if database_type is DatabaseType.RETHINK_DB:
        from database_driver.plugin.database.nosql.rethinkdb.rethinkdb_database_provider import (
            RethinkDBDatabaseProvider,
        )

        return RethinkDBDatabaseProvider(credentials)
    if database_type is DatabaseType.REDIS:
        from database_driver.plugin.database.nosql.redis.redis_database_provider import RedisDatabaseProvider

        return RedisDatabaseProvider(credentials)
    if database_type is DatabaseType.JSON:
        from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider

        return JsonDatabaseProvider(credentials)
    if database_type is DatabaseType.CSV:
        from database_driver.plugin.database.nosql.csv.csv_database_provider import CSVDatabaseProvider

        return CSVDatabaseProvider(credentials)
    if database_type is DatabaseType.TOML:
        from database_driver.plugin.database.nosql.toml.toml_database_provider import TOMLDatabaseProvider

        return TOMLDatabaseProvider(credentials)

    raise ValueError(f"@DatabaseRepositoryRegistry: unsupported DatabaseType {database_type}")
