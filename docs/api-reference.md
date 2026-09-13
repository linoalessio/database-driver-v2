# API Reference

The complete public surface of both editions, entry by entry. For worked examples, see
[api-usage.md](api-usage.md); for how the pieces fit together, see
[architecture.md](architecture.md). Java names are used throughout; the
[Python naming rules](#python-edition-naming-rules) at the end map them mechanically to the
mirror.

Everything below ships in `database-driver-api` (contracts) unless marked *(plugin)*.

## Async conventions

- **Java**: every operation has an `Async` variant returning a `CompletableFuture`
  (`insert` → `insertAsync`, …), offloaded to the common pool. Two overrides are genuinely
  parallel rather than one-task wrappers: `DatabaseRepositoryRegistry.shutdownAsync()` and
  `SQLDatabaseProvider.clearAsync()`.
- **Python**: every operation has a `*_async` coroutine (`insert` → `insert_async`), offloaded
  via `asyncio.to_thread`. The only natively-async surface is the cache: `Cache.get` and
  `ClusteredCache.get`/`put` are `async def` by contract.

## `DatabaseRepository`

Abstract class; the process-wide entry point. Obtain via `DatabaseRepository.getInstance()`
after constructing `DatabaseRepositoryRegistry(boolean logBytes)` *(plugin)* once.

| Method | Purpose |
|---|---|
| `registerDatabaseProvider(int id, DatabaseType, Credentials)` | Construct, connect and register a provider under a caller-assigned id; returns it. Throws `IllegalStateException` if the id is taken |
| `unregisterDatabaseProvider(int id)` | Remove and shut down the provider; throws if the id is unknown |
| `findDatabaseProviderById(int id)` | `Optional` lookup of a registered provider |
| `getDatabaseProviderPool()` / `getDatabaseProviderPool(DatabaseType)` | Unmodifiable list of all registered providers, optionally filtered by type |
| `convert(int sourceId, int targetId)` | Copy every section and entry from one registered provider into another; target sections of the same name are recreated; a Redis source derives section names by splitting keys on `:`. Returns the `Pair` of both providers |
| `shutdown()` | Shut down every registered provider and stop the TTL sweeper |

## `DatabaseProvider`

One connected backend.

| Method | Purpose |
|---|---|
| `createSection(String name)` | Create (or return the cached) section; implies `SectionConfig.full()` — warm before returning |
| `createSection(String name, SectionConfig)` | Same, with an explicit cache mode. Re-declaring with a different config rebuilds the section (cache state dropped, data kept) |
| `getSection(String name)` | `Optional` lookup without materializing data |
| `getSections()` | All sections (materializes them) |
| `existsSection(String name)` / `deleteSection(String name)` | Presence check / drop the backing table-collection-directory |
| `clear()` | Remove every section from this database |
| `reload()` | Rebuild the section-name list from the backing store; previously returned section instances are detached |
| `shutdown()` | Close connections/pools for this provider |

## `DatabaseSection`

One table / collection / key prefix / directory.

| Method | Purpose |
|---|---|
| `insert(DatabaseEntry)` | Persist a new entry; `DataAlreadyExist` if the id is present |
| `update(DatabaseEntry)` | Persist changes to an existing entry; `NoSuchEntryFound` if absent |
| `delete(String id)` | Remove an entry; `NoSuchEntryFound` if absent |
| `findEntryById(String id)` | `Optional` point read (cache-mode dependent — see [api-usage.md](api-usage.md#2-per-section-cache-modes)) |
| `exists(String id)` / `count()` | Presence / cardinality; answered by the backend in `BOUNDED`/`NONE` modes |
| `getEntries()` | The whole section as one list — prefer the two calls below for large sections |
| `getEntries(long offset, int limit)` | One stable, id-ordered page; `IllegalArgumentException` for negative arguments, empty list past the end |
| `forEachEntry(Consumer)` | Stream every entry in constant memory |
| `clear()` | Remove every entry (the section keeps existing) |
| `reload()` | Rebuild this section's cached view from the backing store |
| `getName()` | The section name |

The concrete section class (`AbstractCachedDatabaseSection`, *plugin*) additionally exposes:

| Member | Purpose |
|---|---|
| `stats()` → `SectionStats(cacheHits, cacheMisses, fullLoads, fullLoadNanos)` | Per-section cache effectiveness counters (approximate under heavy concurrency), plus `cacheHitRatio()` |
| `onExternalInvalidate(String id)` | Evict one entry changed by another process — wire to a change feed (see [`DatabaseNotification`](#databasenotification-and-redis-counters)) |

## `SectionConfig` and `CacheMode`

`SectionConfig` is a record `(CacheMode cacheMode, long maxEntries, Duration ttl)`; behaviorally
identical configs compare equal (non-`BOUNDED` modes normalize `maxEntries`/`ttl`), which is
what `createSection` uses to decide reuse-vs-rebuild. Factories:

| Factory | `CacheMode` | Meaning |
|---|---|---|
| `SectionConfig.full()` *(default)* | `FULL` | every entry in memory, loaded before `createSection` returns |
| `SectionConfig.lazy()` | `LAZY` | every entry in memory, loaded on first data access |
| `SectionConfig.bounded(maxEntries)` | `BOUNDED` | read-through LRU, at most `maxEntries` in heap |
| `SectionConfig.bounded(maxEntries, ttl)` | `BOUNDED` | same, entries also expire `ttl` after caching |
| `SectionConfig.none()` | `NONE` | nothing cached; every call hits the backend |

`bounded(...)` requires positive `maxEntries` and a positive (or absent) `ttl`.

## `DatabaseType`

All 14 constants, with what each edition needs to use them:

| Constant | Kind | Java driver (bundled in `-plugin`?) | Python driver (pip extra) |
|---|---|---|---|
| `MY_SQL` | SQL | `com.mysql.cj.jdbc.Driver` — **not bundled** | `pymysql` (`mysql`) |
| `MARIA_DB` | SQL | `org.mariadb.jdbc.Driver` — bundled | `pymysql` (`mysql`) |
| `POSTGRES_SQL` | SQL | `org.postgresql.Driver` — bundled | `psycopg` v3 (`postgres`) |
| `SQLITE` | SQL | `org.sqlite.JDBC` — bundled | stdlib `sqlite3` (no extra) |
| `H2_DB` | SQL | `org.h2.Driver` — bundled | — raises `NotImplementedError` |
| `APACHE_DERBY` | SQL | `org.apache.derby.jdbc.EmbeddedDriver` — **not bundled**; runs embedded in-memory | — raises `NotImplementedError` |
| `ORACLE` | SQL | `oracle.jdbc.OracleDriver` — **not bundled** | `oracledb` (`oracle`) |
| `MICROSOFT_SQL_SERVER` | SQL | `com.microsoft.sqlserver.jdbc.SQLServerDriver` — **not bundled** | `pymssql` (`mssql`) |
| `MONGO_DB` | NoSQL | MongoDB Java driver — bundled | `pymongo` (`mongodb`) |
| `RETHINK_DB` | NoSQL | RethinkDB Java driver — bundled | `rethinkdb` (`rethinkdb`) |
| `REDIS` | NoSQL | Jedis — bundled | `redis` (`redis`) |
| `JSON` | file store | none needed | none needed |
| `TOML` | file store | toml4j — bundled | `tomli-w` (hard dependency) + stdlib `tomllib` |
| `CSV` | file store | none needed | none needed |

Each constant also carries a `type` string (the JDBC URL sub-protocol, e.g. `"postgresql"`,
`"oracle:thin"`) and driver metadata: the Java enum's `driverClass` is the JDBC class name the
connection pool is pointed at for the SQL backends (the NoSQL constants carry placeholder values
no code path reads), the Python enum's `driver_package` the import name of the driver the plugin
actually connects through (`"NULL"` where no driver is involved). The Python field is
informational — the plugin's lazy imports name their modules directly — and matches the table
above.

## `DatabaseEntry`, `JsonDocument`, `Serialized`

**`DatabaseEntry`** — immutable `(String id, JsonDocument document)`; `getMetaData()` returns
the document's `"data"` payload (the entry's actual content).

**`JsonDocument`** — the schema-less document model, wrapping a Gson `JsonObject` (Java) or a
plain `dict` (Python):

- Constructors from nothing, bytes, string, stream, reader, file, `JsonObject`/`JsonElement`,
  or a single key-value pair.
- Fluent `append(key, value)` overloads for strings, numbers, booleans, characters, documents,
  maps, byte arrays and lists; `remove`, `clear`, `contains`, `getKeys`, `copy`, `asMap`.
- Typed readers (`getString`, `getInteger`, `getLong`, `getDouble`, `getBoolean`,
  `getBigDecimal`, …) that throw when the key is absent, and generic `get(key, type)` /
  `get(key, type, default)` / `get(key, type, default, predicate)` deserialization via Gson.
- I/O: `write(Path|File|String)`, `toJson()`, `toBytes()`, and `JsonDocument.load(Path)`
  (returns an **empty document** on a missing or unparseable file rather than throwing).
- Caveat: `append(String, byte[])` stores Base64 while `getBinary(String)` reads back through
  `BigInteger` — the two are not inverses; round-trip binary data through your own encoding.

**`Serialized`** — base class for domain entities: `keysOf()` (identifying values, first is
the primary key), `primaryKey()`, `hasKey(String)` (case-insensitive), `keysAsString()`, and
JSON byte round-tripping via `toByteArray()` / `fromByteArray(bytes, type)`.

## Exceptions and error conventions

| Exception | Raised when |
|---|---|
| `DataAlreadyExist` | `insert` with an id already present |
| `NoSuchEntryFound` | `update`/`delete` on an absent id |
| `NoSuchDataFound` | a stored record lacks its `data` payload (corrupt or foreign data) |

All are unchecked (`RuntimeException` / `RuntimeError`). Absence on reads is expressed by
`Optional` (Java) / `None` (Python), never by exception; invalid paging arguments raise
`IllegalArgumentException` / `ValueError`; duplicate or unknown registry ids raise
`IllegalStateException` / `RuntimeError`.

## `Cache`, `ClusteredCache`, `Caches`

Obtained through the static `Caches` factory, never constructed directly. The api module ships
the contracts and a `CacheProvider` SPI; the plugin registers `DefaultCacheProvider` via
`ServiceLoader` (Java) or the `database_driver.cache_provider` entry-point group (Python).
`Caches` throws `IllegalStateException` / `RuntimeError` when no provider is on the classpath.

| Entry point | Purpose |
|---|---|
| `Caches.newCache(loader, ttl, maxSize)` | Async read-through cache: `get` never blocks, concurrent misses on one key share a single in-flight load (stampede protection); `ttl` null = no expiry, `maxSize <= 0` = unbounded |
| `Caches.newClusteredCache(shardCount, replicationFactor, loader, ttl, maxSizePerShard)` | Shards across consistent-hash-selected `Cache` instances; `get` reads the primary replica, `put` writes all replicas in parallel — single-JVM simulation of Cassandra-style partitioning |

`Cache` methods: `get(id)`, `put(id, value)`, `invalidate(id)`, `invalidateAll()`,
`evictExpired()` (O(n), call from a scheduler), `size()`, `snapshot()`.
`ClusteredCache` methods: `get`, `put`, `invalidate`, `totalSize()`, `shardCount()`.
`ConsistentHashRing` (also public): `addNode`, `removeNode`, `nodeFor(key)`,
`nodesFor(key, replicationFactor)`.

Implementation facts *(plugin)*: TTL is absolute from insert (no refresh-on-read); size
eviction is approximate LRU (sample of 8); failed loads are not cached; TTL-bearing caches are
swept every 60 s by the shared sweeper thread.

## Export contracts and `ExportCoordinator`

Contracts in `de.lino.database.utils.export`:

| Contract | Shape |
|---|---|
| `DataExporter` | `export(rows, headers, rowMapper, title, output)` — flat table, one column per header. **No implementation ships**; callers supply their own |
| `TranscriptExporter` | `export(documentTitle, columnHeaders, sections, legendTitle, legendEntries, pageLayout, output)` — grouped, section-based documents |
| `ArchiveExporter` | `export(output)` — whole-directory archive to one file |
| `ExporterInjector` | `injectDataExporter(...)` / `injectArchiveExporter(...)` |
| `ExportType` | `PDF(pdf)`, `EXCEL(xlsx)`, `CSV(csv)`, `XML(xml)`, `JSON(json)`, `DOCX(docx)`; `fromSuffix(fileName)` matches the extension case-insensitively |
| `TranscriptSection` / `TranscriptLegendEntry` | `(title, rows)` / `(label, description)` value types |
| `PageLayout` (`PageFormat` A3/A4/A5 × `PageOrientation`) | Page geometry; `PageLayout.DEFAULT` = A4 portrait; only affects PDF, Excel and DOCX renderings |

`ExportCoordinator` *(plugin)* wires them together: `exportTable` and `exportArchive` require
prior injection (`IllegalStateException` / `RuntimeError` otherwise; `DirectoryZipExporter` is
the one built-in `ArchiveExporter`); `exportTranscript` needs no injection — it auto-selects
one of six private built-in exporters from the output file's extension. Not thread-safe with
respect to swapping injected exporters mid-export. Java renders PDF via Apache PDFBox and
XLSX/DOCX via Apache POI (bundled); Python uses reportlab/openpyxl/python-docx behind the
`export` extra — without it, only the stdlib formats (CSV/XML/JSON) and ZIP archives work.

## `DatabaseNotification` and Redis counters

`DatabaseNotification` (contract): `watch(entityTypes...)`, `start(onNotification)`,
`shutdown()`, `getChannel()`, `isRunning()`, `getThread()`. There is deliberately no
vendor-agnostic implementation — each backend's push primitive differs too much to unify.

| Implementation *(plugin)* | Mechanism |
|---|---|
| `PostgresDatabaseNotification` | `watch` installs an idempotent `AFTER INSERT OR UPDATE` trigger per entity table (DDL runs as one transaction) that `pg_notify`s `{"table", "operation", "id"}` — never row data; `start` blocks a daemon thread on a dedicated, non-pooled connection |
| `RedisDatabaseNotification` (Python: same name; Java class ships in `database.nosql.redis`) | Subscribes a dedicated client to a Pub/Sub channel; `watch` is a no-op (Redis needs no triggers). Redis sections publish the same `{"table", "operation", "id"}` payload on the fixed channel `database-driver-changes` on every insert/update |

`RedisCounterService` (contract; `JedisRedisCounterService` in Java,
`RedisPyCounterService` in Python, both obtained via `RedisDatabaseProvider.counterService()`):
`incrementAndGetWithExpiry(key, windowSeconds)` — one atomic Lua `INCR`+`EXPIRE`-on-first-hit,
for fixed-window rate limiting — plus `getCount(key)` and `reset(key)`.

## `Credentials`

See [configuration.md](configuration.md) — the class is a self-seeding config file, and that
page owns its semantics, constructors and key table.

## `FileProvider`

The filesystem abstraction used internally by `JsonDocument.write` and the file stores
(create/delete/rename/copy files and directories, recursive directory operations). Installed
as a singleton by `DatabaseRepositoryRegistry`; consumers rarely touch it directly.

## Python edition naming rules

The mirror translates the contract only at the language boundary:

| Java | Python |
|---|---|
| `CamelCase` methods | `snake_case` (`findEntryById` → `find_entry_by_id`) |
| `...Async()` → `CompletableFuture` | `..._async()` coroutine (`asyncio.to_thread`) |
| `Optional<T>` returns | `T \| None` |
| constructor/method overloads | keyword arguments with defaults |
| `Caches` class | `database_driver.api.utils.cache.provider.caches` module (`new_cache`, `new_clustered_cache`) |
| `getEntries(offset, limit)` | `get_entries_page(offset, limit)` |

Import roots: `database_driver.api` (32 re-exported names) and `database_driver.plugin`
(11 re-exported names). Every Java class maps to one Python module at the identical package
path. Deliberate behavioral deviations are listed in the
[README's Python Edition section](../README.md#python-edition).
