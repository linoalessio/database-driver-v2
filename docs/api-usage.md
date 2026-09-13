# API Usage Guide

Task-oriented examples for each consumer audience. For the complete surface — every method,
every `DatabaseType`, every contract — see [api-reference.md](api-reference.md); for
connection configuration, see [configuration.md](configuration.md).

| You are… | Use | Section |
|---|---|---|
| writing a Java application against the driver | `database-driver-api` + `-plugin` | [§1](#1-java-the-core-crud-path) – [§5](#5-push-notifications) |
| writing a Python application against the mirror | `lino-database-driver-api` + `-plugin` | [§6](#6-python-edition) |

All examples assume the packages are installed — see [getting-started.md](getting-started.md).

## 1. Java: the core CRUD path

Initialize the repository once, in your main class. Constructing
`DatabaseRepositoryRegistry` installs the `DatabaseRepository` singleton and the internal
`FileProvider`; pass `true` to log the byte size of every inserted/updated document.

```java
new DatabaseRepositoryRegistry(/* logBytes = */ false);
```

Register a provider, then work through sections and entries:

```java
// Credentials seeds a config file on first use and reads it back afterwards
// (see docs/configuration.md).
final Credentials credentials = new Credentials(Paths.get("config/database.json"), Paths.get("data"));

final DatabaseProvider provider = DatabaseRepository.getInstance()
        .registerDatabaseProvider(1, DatabaseType.JSON, credentials);

final DatabaseSection players = provider.createSection("players");

// Insert: first argument is the entry id, second is the document to store.
players.insert(new DatabaseEntry("Lino", new JsonDocument("name", "lino").append("age", 23)));

// Read: findEntryById returns an Optional.
final DatabaseEntry entry = players.findEntryById("Lino").orElseThrow();

// Update: mutate the metadata document, then persist it. Any Gson-serializable
// type can be appended.
entry.getMetaData().remove("age").append("country", "germany").append("pet", new Pet("Rocco", "Golden Retriever"));
players.update(entry);

players.exists("Lino");   // true
players.count();          // 1
players.delete("Lino");
```

The stored document for that entry:

```json
{
  "id": "Lino",
  "data": {
    "name": "lino",
    "country": "germany",
    "pet": { "name": "Rocco", "kind": "Golden Retriever" }
  }
}
```

Swapping backends means changing the `DatabaseType` and the credentials — nothing else.
Shut everything down on exit:

```java
DatabaseRepository.getInstance().shutdown();
```

### Reading large sections

`getEntries()` materializes the whole section. For large ones, stream or page instead:

```java
// Constant memory, regardless of section size — every backend reads in bounded batches.
players.forEachEntry(entry -> System.out.println(entry.getId()));

// Stable, id-ordered pages: the same arguments yield the same page while the data is
// unchanged, in every cache mode and on every backend. An offset past the end returns [].
final List<DatabaseEntry> page = players.getEntries(/* offset */ 0, /* limit */ 100);
```

### Asynchronous variants

Append `Async` to any operation to get a `CompletableFuture` instead of a blocking call:

```java
players.findEntryByIdAsync("Lino")
        .thenApply(Optional::orElseThrow)
        .thenAccept(entry -> System.out.println(entry.getMetaData().getString("name")));
```

### Managing providers and sections

```java
DatabaseRepository.getInstance().findDatabaseProviderById(1);        // Optional<DatabaseProvider>
DatabaseRepository.getInstance().getDatabaseProviderPool();          // all providers
DatabaseRepository.getInstance().getDatabaseProviderPool(DatabaseType.JSON);
DatabaseRepository.getInstance().unregisterDatabaseProvider(1);      // shuts the provider down

provider.existsSection("players");
provider.getSection("players");     // Optional, without materializing data
provider.getSections();
provider.deleteSection("players");  // drops the backing table/collection/directory
provider.clear();                   // removes every section
provider.reload();                  // rebuild the section list from the backing store
```

`reload()` on a provider or a section discards this object's cached view and rebuilds it from
the backing store — for the case where something changed the store from outside (a restored
backup, another process). A provider `reload()` detaches previously returned section
instances, so re-fetch them via `getSection`.

### Copying a whole database

```java
// Copies every section and entry from provider 1 into provider 2; both must be registered.
DatabaseRepository.getInstance().convert(1, 2);
```

## 2. Per-section cache modes

By default a section holds **all** entries in memory, loaded when `createSection(name)`
returns. That is ideal for small hot tables and unaffordable for large ones, so the trade-off
is configurable per section via `createSection(String, SectionConfig)`:

| `SectionConfig` | Heap held | Read path | Intended for |
|---|---|---|---|
| `full()` *(default)* | all entries | in-memory map, zero I/O | small hot tables |
| `lazy()` | all entries, after first access | one-time load on first data access, then as `full()` | hot tables that must not cost startup time |
| `bounded(maxEntries)` | at most `maxEntries` | hit from memory, miss point-read from the backend (LRU-evicted) | large tables with a hot working set |
| `bounded(maxEntries, ttl)` | at most `maxEntries` | as `bounded(n)`, entries also expire `ttl` after caching | the same, when other processes also write the table |
| `none()` | nothing | every operation pushed to the backend | unbounded append-mostly tables (logs, history) |

```java
final DatabaseSection hot      = provider.createSection("settings",   SectionConfig.full());
final DatabaseSection deferred = provider.createSection("statistics", SectionConfig.lazy());
final DatabaseSection working  = provider.createSection("players",    SectionConfig.bounded(10_000));
final DatabaseSection shared   = provider.createSection("sessions",   SectionConfig.bounded(10_000, Duration.ofMinutes(5)));
final DatabaseSection logs     = provider.createSection("logs",       SectionConfig.none());
```

Worth knowing:

- **`createSection(name)` still means `full()`**, warm by the time it returns — every
  pre-existing call site behaves exactly as before. Connecting a provider, however, loads
  nothing by itself: sections nobody asks for are never read.
- **Writes are write-through in every mode.** Each `insert`/`update`/`delete` persists
  immediately and then synchronizes cache state, so a process always reads its own writes.
  Exceptions behave identically in every mode.
- **Re-declaring a section re-configures it.** The same config returns the existing instance;
  a different one rebuilds the section, dropping cache state but never data.
- **`bounded(...)`/`none()` answer `count()`/`exists(...)`/`getEntries()` from the backend**, so
  external writes are visible immediately there; `full()`/`lazy()` need a `reload()`.
- **Cache effectiveness is measurable** on the concrete section class:

```java
final AbstractCachedDatabaseSection section = (AbstractCachedDatabaseSection) working;

final AbstractCachedDatabaseSection.SectionStats stats = section.stats();
stats.cacheHits(); stats.cacheMisses(); stats.fullLoads(); stats.cacheHitRatio();

// Evict an entry another process changed — wire this to a change feed (§5).
section.onExternalInvalidate("Lino");
```

## 3. Exporting data

`ExportCoordinator` offers three export kinds. Flat-table and archive exports use **interface
injection** — the coordinator never constructs those exporters itself — while transcript
exports auto-select a built-in implementation from the output file's extension.

```java
final ExportCoordinator coordinator = new ExportCoordinator();

// One section per group; each inner list is one row's cells.
final List<TranscriptSection> sections = List.of(
        new TranscriptSection("WiSe 24/25", List.of(
                List.of("#1", "Grundlagen ML", "1.7", "bestanden"),
                List.of("#2", "Datenbanksysteme", "2.3", "bestanden")
        )),
        new TranscriptSection("SoSe 25", List.of(
                List.of("#3", "IAP Labor", "1.3", "bestanden")
        ))
);

final List<TranscriptLegendEntry> gradingScale = List.of(
        new TranscriptLegendEntry("1.0 – 1.5", "sehr gut (excellent)"),
        new TranscriptLegendEntry("1.7 – 2.5", "gut (good)")
);

// No injection needed: .pdf, .xlsx, .csv, .xml, .json and .docx are all resolved from the
// extension. PageLayout only visibly affects the PDF, Excel and DOCX renderings.
coordinator.exportTranscript(
        "Transcript",
        List.of("Id", "Module", "Grade", "Status"),
        sections,
        "Grading Scale",
        gradingScale,
        PageLayout.DEFAULT,
        Path.of("transcript.pdf")
);
```

Archive and flat-table exports require injection first:

```java
// DirectoryZipExporter is the one built-in ArchiveExporter; the optional second constructor
// argument is a hook run before zipping (e.g. flush an in-memory cache to disk).
coordinator.injectArchiveExporter(new ExportCoordinator.DirectoryZipExporter(Path.of("/var/data/app")));
coordinator.exportArchive(Path.of("backup.zip"));

// No default DataExporter ships — exporting a flat table means supplying your own.
coordinator.injectDataExporter(myDataExporter);
```

Calling `exportTable`/`exportArchive` before the matching injection throws
`IllegalStateException`; an unrecognized transcript extension throws
`IllegalArgumentException`.

## 4. Using the cache directly

The async cache the driver's own `BOUNDED` mode is built on is available to any consumer,
without depending on an implementation class:

```java
// The loader runs only on a miss; concurrent requests for the same id share one in-flight load.
final Cache<String, DatabaseEntry> entryCache = Caches.newCache(
        id -> players.findEntryByIdAsync(id).thenApply(Optional::orElseThrow),
        Duration.ofMinutes(5), // ttl, null for no expiry
        10_000                 // maxSize, <= 0 for unbounded
);

final CompletableFuture<DatabaseEntry> future = entryCache.get("Lino");  // never blocks
entryCache.put("Lino", updatedEntry);   // write through, bypassing the loader
entryCache.invalidate("Lino");
entryCache.evictExpired();              // O(n): call from a scheduler, not the hot path

// Sharded variant: 8 shards, each key replicated to 2 of them (single-JVM partitioning).
final ClusteredCache<String, DatabaseEntry> clustered = Caches.newClusteredCache(
        8, 2,
        id -> players.findEntryByIdAsync(id).thenApply(Optional::orElseThrow),
        Duration.ofMinutes(5),
        1_000 // maxSize PER shard
);

clustered.put("Lino", updatedEntry).join();   // writes all replica shards in parallel
clustered.get("Lino").join();                 // reads the primary replica
```

## 5. Push notifications

To react to writes the instant they happen instead of polling, use `DatabaseNotification`.
PostgreSQL's implementation needs two things from a table: a **trigger** (installed once via
`watch`) and a **listener** (started via `start`).

```java
// credentials must point at the same Postgres database the watched tables live in.
final DatabaseNotification notification = new PostgresDatabaseNotification(credentials, "entry_changes");

// Install the trigger per entity type — table name is the class's simple name. Call after
// createSection has run for that type; types first persisted after start() need their own
// watch() call.
notification.watch(Exam.class);

// One callback per row write, from any writer in any process. The payload carries "table",
// "operation" and "id" — never the row's own data.
notification.start(payload -> System.out.println(
        payload.getString("table") + " " + payload.getString("operation") + " " + payload.getString("id")));

notification.isRunning();
notification.getChannel();
notification.shutdown();   // start() can be called again afterwards
```

Redis sections publish the same `{"table", "operation", "id"}` payload on the fixed Pub/Sub
channel `database-driver-changes` on every insert and update, so any Redis client can consume
it with identical payload handling. Pair either feed with a section's
`onExternalInvalidate(id)` hook (§2) to evict entries another process changed.

The Postgres trigger assumes the exact `(id TEXT, data BYTEA)` schema `SQLDatabaseSection`
creates — re-verify that if you pin a different plugin version.

## 6. Python edition

The same contract, translated only at the language boundary: `snake_case` methods, `*_async`
coroutines, `None` instead of `Optional`, keyword arguments instead of overloads.

```python
from pathlib import Path

from database_driver.api import (
    Credentials, DatabaseEntry, DatabaseRepository, DatabaseType, JsonDocument, SectionConfig,
)
from database_driver.plugin import DatabaseRepositoryRegistry

DatabaseRepositoryRegistry(log_bytes=False)

credentials = Credentials(Path("config/database.json"), file_repository=Path("data"))
provider = DatabaseRepository.get_instance().register_database_provider(1, DatabaseType.JSON, credentials)

players = provider.create_section("players")
players.insert(DatabaseEntry("Lino", JsonDocument("name", "lino").append("age", 23)))

entry = players.find_entry_by_id("Lino")          # DatabaseEntry | None
if entry is not None:
    print(entry.get_meta_data().get_string("name"))

# Cache modes, streaming and paging mirror the Java edition one-for-one.
working = provider.create_section("players", SectionConfig.bounded(10_000))
players.for_each_entry(lambda e: print(e.id))
page = players.get_entries_page(0, 100)

DatabaseRepository.get_instance().shutdown()
```

Asynchronously:

```python
import asyncio

async def main() -> None:
    entry = await players.find_entry_by_id_async("Lino")
    print(entry.get_meta_data().get_string("name") if entry else "missing")

asyncio.run(main())
```

Caches resolve through module-level functions rather than a class, and the loader is a
coroutine:

```python
from database_driver.api.utils.cache.provider import caches

async def load(entry_id: str):
    return await players.find_entry_by_id_async(entry_id)

cache = caches.new_cache(load, ttl=None, max_size=10_000)
entry = await cache.get("Lino")
```

Backends whose driver is an unselected pip extra raise `ModuleNotFoundError` when the provider
is constructed; `H2_DB` and `APACHE_DERBY` raise `NotImplementedError`. Transcript exports to
PDF/XLSX/DOCX raise `ModuleNotFoundError` without the `export` extra — the stdlib formats
(CSV/XML/JSON) and ZIP archives always work.

## Where to go next

- [api-reference.md](api-reference.md) — the complete surface
- [configuration.md](configuration.md) — connection details and secrets
- [architecture.md](architecture.md) — how the engine and backends fit together
- [contributing.md](contributing.md) — the rules a change must follow
