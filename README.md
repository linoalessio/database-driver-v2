# DatabaseDriver — Multiselect Database Management System

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Build](https://img.shields.io/badge/Build-Maven-C71A36)
![Version](https://img.shields.io/badge/Version-1.3.10-blue)

DatabaseDriver is a management system for multiple SQL and NoSQL database types, controlled
through a single, unified interface. Instead of learning a separate API for every backend, you
work against `DatabaseRepository`, `DatabaseProvider`, `DatabaseSection` and `DatabaseEntry` —
the same four abstractions regardless of whether the data actually lives in MySQL, MongoDB, Redis
or a plain directory of JSON or CSV files. Every operation is also available in a non-blocking,
`CompletableFuture`-based variant.

## Project Structure

The project is split into two Maven modules:

| **Module**                | **Artifact**            | **Contents**                                                                                                                                                    |
|----------------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `database-driver-api`      | `database-driver-api`    | The public API: `DatabaseRepository`, `DatabaseProvider`, `DatabaseSection`, `DatabaseEntry`, `Credentials`, the JSON document model (`JsonDocument`), the driver's exceptions, the `Cache`/`ClusteredCache` contracts, and the generic `EntityFactory`/`FactoryType` entity registry. |
| `database-driver-plugin`   | `database-driver-plugin` | The concrete implementation: `DatabaseRepositoryRegistry`, one `DatabaseProvider`/`DatabaseSection` pair per supported database technology, the default `Cache`/`ClusteredCache` implementations, and `DefaultEntityFactory`.                    |

You depend on `database-driver-api` at compile time to program against the interfaces, and on
`database-driver-plugin` at runtime to actually obtain a working `DatabaseRepository`.

## Supported SQL and NoSQL Databases

### Relational (SQL) Databases

| **Database**                                                       | **Description**                                                                                                                       |
|----------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| [MySQL](https://www.mysql.com/)                                    | Widely used for web applications, content management systems (e.g., WordPress), and general relational data storage.                    |
| [MariaDB](https://mariadb.org)                                     | Drop-in replacement for MySQL with improved performance, security features, and enterprise support; used in web and cloud applications.  |
| [PostgreSQL](https://www.postgresql.org/)                          | Advanced relational database for complex queries, analytics, GIS (geospatial data), and enterprise applications needing strong standards compliance. |
| [SQLite](https://www.sqlite.org)                                   | File-based, serverless database often used in mobile apps, embedded systems, small desktop tools, and prototyping.                       |
| [H2 Database](https://www.h2database.com)                          | Lightweight, in-memory or embedded database mainly for development, testing, or small applications where fast setup is needed.           |
| [Apache Derby](https://db.apache.org/derby)                        | Runs in-process (embedded), so it's ideal for small apps or unit tests. Not recommended for high-traffic production.                      |
| [Microsoft SQL Server](https://www.microsoft.com/de-de/sql-server) | Strong integration with the Microsoft ecosystem. Scales well for medium to large enterprise apps.                                        |
| [Oracle Database](https://www.oracle.com/database/)                | Designed for high concurrency, reliability, and large datasets. Often used in industries that need high availability and complex transactions. |

### Non-Relational (NoSQL) Databases

| **Database**                         | **Description**                                                                                                                                                                                                 |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [MongoDB](https://www.mongodb.com/)   | Document-oriented NoSQL database, great for handling flexible, semi-structured data (e.g., JSON), often used in scalable web and cloud apps.                                                                     |
| [RethinkDB](https://rethinkdb.com)    | Real-time NoSQL database optimized for apps requiring live updates and push notifications (e.g., chat apps, dashboards).                                                                                         |
| JSON File Store                       | Very simple storage solution using local JSON files; suitable for small projects, configs, or prototyping without the overhead of a full database server.                                                       |
| CSV File Store                        | Flat-file storage using one CSV file per section (one row per entry); like the JSON file store but keeps a whole section in a single file instead of one file per entry. Both columns are Base64-encoded so arbitrary ids/documents always round-trip safely, so the raw file isn't meant to be hand-edited. |
| [Redis](https://redis.io)             | Redis is an open-source, in-memory data store used worldwide for high-speed data storage and retrieval. It powers applications as a cache, database, and message broker, enabling real-time analytics, fast session management, and scalable messaging systems. |

> **Note:** The `database-driver-plugin` module ships JDBC drivers for PostgreSQL, H2, SQLite and
> MariaDB (plus the MongoDB, RethinkDB and Jedis/Redis clients) out of the box. It does **not**
> bundle drivers for **MySQL**, **Oracle**, **Microsoft SQL Server** or **Apache Derby** — add the
> corresponding JDBC driver as an extra dependency in your project if you use one of these.

## Installation

Get the source via git:

```
git clone https://github.com/linoalessio/database-driver-v2.git
```

Or add it as a Maven dependency (replace `%version%` with the version you want to use, currently
`1.3.10`). `database-driver-api` gives you the interfaces to code against; `database-driver-plugin`
provides the actual implementations and must be present on the runtime classpath. The artifacts
are published to **GitHub Packages**, not Maven Central, so two extra steps are required before
the dependencies below will resolve.

**1. Point Maven at the package registry** by adding this repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub LinoAlessio Apache Maven Packages</name>
    <url>https://maven.pkg.github.com/linoalessio/database-driver-v2</url>
  </repository>
</repositories>
```

**2. Authenticate.** GitHub Packages requires a logged-in request for every download — including
this public repository. Create a
[personal access token](https://github.com/settings/tokens) with the **`read:packages`** scope,
then add a matching server entry to your `~/.m2/settings.xml` (do **not** hardcode the token in
the file — reference an environment variable instead):

```xml
<settings>
  <servers>
    <server>
      <id>github</id> <!-- must match the <id> used in the <repository> block above -->
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx   # the token you generated above
```

**3. Declare the dependencies:**

```xml
<dependencies>
  <dependency>
    <groupId>de.lino.database</groupId>
    <artifactId>database-driver-api</artifactId>
    <version>%version%</version>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>de.lino.database</groupId>
    <artifactId>database-driver-plugin</artifactId>
    <version>%version%</version>
  </dependency>
</dependencies>
```

--- ---
## DatabaseDriver API

Before working with the driver, make sure a `DatabaseRepository` instance is initialized in your
*main class*. All operations can be **executed asynchronously**: add the suffix ***`Async`*** to
any method and a ***[CompletableFuture](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)***
is returned instead.

```java
// Initialize the repository instance (also installs the default FileProvider used internally).
// Pass 'true' to log the byte size of every inserted/updated document to stdout.
new DatabaseRepositoryRegistry(/* logBytes = */ false);
```

*Working with the DatabaseRepository*
```java
/*
* Credentials automatically creates a config file if it doesn't exist yet, otherwise the
* connection details are loaded from the existing file.
*
* Register a DatabaseProvider under an id (int), a databaseType and the given credentials.
* The method returns the newly created DatabaseProvider.
*
* DatabaseType SQL:   MY_SQL, POSTGRE_SQL, H2_DB, MARIA_DB, SQLITE, ORACLE, MICROSOFT_SQL_SERVER, APACHE_DERBY
* DatabaseType NoSQL: MONGO_DB, RETHINK_DB, JSON, CSV, REDIS
*/
final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(id, databaseType, credentials);

/*
* Get a DatabaseProvider from the cache by its registered id.
* Returns an Optional<DatabaseProvider> for safe error handling.
*/
final DatabaseProvider cachedDatabaseProvider = DatabaseRepository.getInstance().findDatabaseProviderById(id).orElse(null);

/*
* Unregister an existing DatabaseProvider by id.
* The connection to the database is shut down automatically.
*/
DatabaseRepository.getInstance().unregisterDatabaseProvider(id);

// Shut down every registered DatabaseProvider
DatabaseRepository.getInstance().shutdown();

// Get all registered database providers
final List<DatabaseProvider> providerPool = DatabaseRepository.getInstance().getDatabaseProviderPool();

// Get all registered database providers of a specific type
final List<DatabaseProvider> providerByTypePool = DatabaseRepository.getInstance().getDatabaseProviderPool(databaseType);

/*
* Copy every section and entry of one DatabaseProvider (sourceId) into another (targetId).
* Both providers must already be registered. Existing sections of the same name on the target
* are recreated; for a Redis source, section names are derived by splitting each key on ':'.
*/
DatabaseRepository.getInstance().convert(sourceId, targetId);
```

*Working with a DatabaseProvider*
```java
/*
* Create a section/table with the given name; if it already exists, the cached section is
* returned instead.
*/
final DatabaseSection databaseSection = databaseProvider.createSection(name);

// Delete a section/table if it exists
databaseProvider.deleteSection(name);

// Check whether a section exists
final boolean sectionExists = databaseProvider.existsSection(name);

/*
* Get a specific section from the cache.
* Returns an Optional<DatabaseSection>.
*/
final DatabaseSection cachedSection = databaseProvider.getSection(name).orElseThrow();

// Get all registered sections
final List<DatabaseSection> sectionPool = databaseProvider.getSections();

// Remove every section from this database
databaseProvider.clear();

/*
* Discard this database's own cached view of which sections exist and rebuild it from
* the backing store - e.g. after a backup was restored directly onto disk while this
* database was already running, which its own cache would otherwise never notice.
* Every database this module ships caches its section list, so this always does real
* work; it does not affect any DatabaseSection obtained before the call, since the
* rebuilt section list holds entirely new instances - re-fetch via getSection instead.
*/
databaseProvider.reload();

// Shut down this database
databaseProvider.shutdown();
```

*Working with a DatabaseSection*
```java
/*
* Insert a new DatabaseEntry. The first constructor argument is the id, the second is the
* document to store.
*/
final DatabaseEntry entry = new DatabaseEntry("Lino", new JsonDocument("name", "lino").append("age", 23));
databaseSection.insert(entry);

/*
* Update the metadata of an existing entry.
* findEntryById returns an Optional<DatabaseEntry> for safe error handling.
*/
final DatabaseEntry existingEntry = databaseSection.findEntryById("Lino").orElse(null);
final Pet dog = new Pet("Rocco", "Golden Retriever"); // any user-defined, Gson-serializable type
existingEntry.getMetaData().remove("age").append("country", "germany").append("pet", dog);
databaseSection.update(existingEntry);

// Delete an existing entry by id
databaseSection.delete(id);

// Check whether an entry with the given id exists
final boolean isEntry = databaseSection.exists(id);

// Remove every entry from this section (the section itself keeps existing)
databaseSection.clear();

/*
* Discard this section's own cached view of its entries and rebuild it from the
* backing store - the section-level counterpart of DatabaseProvider#reload(), for the
* same "something changed the backing store outside this object" situation.
*/
databaseSection.reload();

// To remove the section itself, delete it through its database instead:
databaseProvider.deleteSection(databaseSection.getName());

// Count all entries
final long count = databaseSection.count();

// Get all existing entries
final List<DatabaseEntry> entries = databaseSection.getEntries();
```

Resulting `DatabaseEntry` with id `"Lino"` and its `"data"` payload:
```json
{
  "id": "Lino",
  "data": {
    "name": "Lino",
    "country": "germany",
    "pet": {
      "name": "Rocco",
      "kind": "Golden Retriever"
    }
  }
}
```

*Database Credentials*
```java
// SQL — network-based backends
final Credentials mySQL          = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials mariadb        = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials postgreSQL     = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials oracle         = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials microsoftServer = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials apacheDerby    = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database"); // address/port are unused for the embedded driver

// SQL — file-based backends: fileRepository is the database file *without* extension,
// the driver appends the correct suffix itself (SQLite -> ".sqlite")
final Credentials sqlite = new Credentials(Paths.get("CONFIG_PATH"), Paths.get("DATABASE_NAME"));
final Credentials h2db   = new Credentials(Paths.get("CONFIG_PATH"), Paths.get("DATABASE_REPOSITORY_PATH"));

// NoSQL — network-based backends
final Credentials mongodb   = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials rethinkDB = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");
final Credentials redis     = new Credentials(Paths.get("CONFIG_PATH"), "address", "userName", "password", port, "database");

// NoSQL — file-based backends: fileRepository is the *directory* the section files are stored in
// (one JSON file per entry for JSON, one CSV file per section for CSV)
final Credentials json = new Credentials(Paths.get("CONFIG_PATH"), Paths.get("DATABASE_REPOSITORY_PATH"));
final Credentials csv  = new Credentials(Paths.get("CONFIG_PATH"), Paths.get("DATABASE_REPOSITORY_PATH"));
```

`Credentials` persists whatever you pass in to `configDestination` as JSON the first time it runs;
on every subsequent run it reads the existing file back instead, so the constructor arguments
other than `configDestination` are only used to seed that file once.

--- ---

## Using `ExportCoordinator`

Applications built on this driver often need to export their data — per-table PDF/Excel
sheets, grouped transcripts, or a full backup of the local database — without that
logic depending on any one application's entities. The `export` package follows the
same api/plugin split as the rest of this driver (see [Project Structure](#project-structure)):
`database-driver-api` ships only the contracts —
[`DataExporter`](database-driver-api/src/main/java/de/lino/database/utils/export/data/DataExporter.java) (flat tables),
[`TranscriptExporter`](database-driver-api/src/main/java/de/lino/database/utils/export/transcript/TranscriptExporter.java) (grouped, section-based documents),
[`ArchiveExporter`](database-driver-api/src/main/java/de/lino/database/utils/export/archiv/ArchiveExporter.java) (whole-directory archives) and
[`ExporterInjector`](database-driver-api/src/main/java/de/lino/database/utils/export/ExporterInjector.java) — while
`database-driver-plugin` ships the single, application-agnostic access point that wires
them together,
[`ExportCoordinator`](database-driver-plugin/src/main/java/de/lino/database/utility/export/ExportCoordinator.java).
`exportTable` and `exportArchive` never construct a concrete exporter themselves; a
caller hands one in through `ExporterInjector`'s setter methods, a.k.a. **interface
injection** — no default `DataExporter` ships with this module, so exporting a flat
table always means supplying your own, while `DirectoryZipExporter` ships as
`ExportCoordinator`'s one built-in `ArchiveExporter`. `exportTranscript` takes the
opposite approach and involves no injection at all: every call auto-detects the
implementation to write with from `output`'s file extension (`.pdf`, `.xlsx`, `.csv`,
`.xml`, `.json` or `.docx`), each backed by its own private nested class
(`TranscriptPDFExporter`, `TranscriptExcelExporter`, `TranscriptCSVExporter`,
`TranscriptXMLExporter`, `TranscriptJsonExporter`, `TranscriptDocxExporter`) — see
`ExportType.fromSuffix`. A `PageLayout` (page size + orientation, from the
`de.lino.database.utils.export.transcript.format` package) is passed to every call, though it
only visibly affects the PDF, Excel and DOCX renderings; use `PageLayout.DEFAULT` for A4
portrait. Nothing about `ExportCoordinator`'s coordination logic itself is specific to
any one application — a caller can inject its own `DataExporter`/`ArchiveExporter` just
as easily, from this project or another one entirely. See
[`university-driver`](https://github.com/linoalessio/university-driver) for a real
consumer, binding `DirectoryZipExporter` to its own local database directory.

```java
import de.lino.database.utility.export.ExportCoordinator;
import de.lino.database.utils.export.transcript.TranscriptLegendEntry;
import de.lino.database.utils.export.transcript.TranscriptSection;
import de.lino.database.utils.export.transcript.format.PageLayout;

import java.nio.file.Path;
import java.util.List;

// One section per group; each inner list is one row's cell values.
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

final ExportCoordinator coordinator = new ExportCoordinator();

// DirectoryZipExporter is bound to a source directory (and, optionally, a hook run
// beforehand, e.g. to flush an application's in-memory cache to disk first) - nothing
// about it is specific to this driver's own local database directory. No default
// DataExporter ships with this module; a caller that needs one supplies its own and
// injects it via injectDataExporter the same way.
coordinator.injectArchiveExporter(new ExportCoordinator.DirectoryZipExporter(Path.of("/var/data/app")));

// Grouped, transcript-style export - PDF here, but the implementation is auto-detected
// from output's file extension (.pdf, .xlsx, .csv, .xml, .json, .docx all work, no
// injection needed); PageLayout only visibly affects the PDF, Excel and DOCX renderings.
coordinator.exportTranscript(
        "Transcript",
        List.of("Id", "Module", "Grade", "Status"),
        sections,
        "Grading Scale",
        gradingScale,
        PageLayout.DEFAULT,
        Path.of("transcript.pdf")
);

// A full, format-agnostic backup of the injected source directory, zipped to one file.
coordinator.exportArchive(Path.of("backup.zip"));
```

--- ---

## Using `Cache` / `ClusteredCache`

Anything that needs to cache expensive-to-load values — e.g. a `DatabaseProvider` wrapping its
own entries, or an application built on top of this driver — can use the async cache that ships
alongside the driver, without depending on any implementation class. `database-driver-api` ships
only the `Cache`/`ClusteredCache` contracts and the `Caches` factory; `database-driver-plugin`
ships the actual in-memory implementation and registers it via `java.util.ServiceLoader`, so it
is picked up automatically as long as `database-driver-plugin` is on the runtime classpath —
same api/plugin split as the rest of this driver (see [Project Structure](#project-structure)).

`Cache<ID, T>` is a single, unbounded-by-default key/value cache with an optional TTL and
size limit; `ClusteredCache<ID, T>` partitions entries across multiple shards using consistent
hashing, with an optional replication factor, following the same principle as Cassandra/DynamoDB
(all still within a single JVM — see the `ClusteredCache` javadoc for the honest caveat on
distributing across real machines). Both are obtained through `Caches`, never constructed
directly:

```java
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.ClusteredCache;
import de.lino.database.utils.cache.provider.Caches;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

// A single cache, keyed by DatabaseEntry id. The loader is only called on a cache miss;
// concurrent requests for the same, not-yet-cached id share the same in-flight load.
final Cache<String, DatabaseEntry> entryCache = Caches.newCache(
        id -> databaseSection.findEntryByIdAsync(id).thenApply(Optional::orElseThrow),
        Duration.ofMinutes(5), // ttl, null for unbounded
        10_000                 // maxSize, <= 0 for unbounded
);

// Reads never block; the loader runs asynchronously on a cache miss.
final CompletableFuture<DatabaseEntry> entry = entryCache.get("Lino");

// Write through directly, e.g. right after insert/update, bypassing the loader.
entryCache.put("Lino", updatedEntry);

entryCache.invalidate("Lino");   // drop a single entry
entryCache.evictExpired();       // periodic cleanup, call from a scheduler, not the hot path

// A clustered cache: 8 shards, each key replicated to 2 of them.
final ClusteredCache<String, DatabaseEntry> clusteredCache = Caches.newClusteredCache(
        /* shardCount        */ 8,
        /* replicationFactor */ 2,
        id -> databaseSection.findEntryByIdAsync(id).thenApply(Optional::orElseThrow),
        Duration.ofMinutes(5),
        1_000 // maxSize PER shard
);

clusteredCache.put("Lino", updatedEntry).join(); // writes to all replica shards in parallel
final DatabaseEntry clusteredEntry = clusteredCache.get("Lino").join();
```

--- ---

## Using `EntityFactory`

Applications built on this driver typically need to keep a set of domain entities - grouped into
logical types - available both as a fast in-memory registry and, when needed, persisted through
one of the `DatabaseProvider`s above. Rather than one project hard-coding a closed `enum` of
entity types together with their database section names and concrete classes, this driver ships a
generic version: same api/plugin split as the rest of this driver (see
[Project Structure](#project-structure)) -
[`EntityFactory`](database-driver-api/src/main/java/de/lino/database/database/factory/EntityFactory.java)
and [`FactoryType`](database-driver-api/src/main/java/de/lino/database/database/factory/FactoryType.java)
are the contracts, and
[`DefaultEntityFactory`](database-driver-plugin/src/main/java/de/lino/database/database/factory/DefaultEntityFactory.java)
is the implementation.

`EntityFactory` works against **any** `enum` constant you pass in as the grouping tag - it never
needs a shared interface or a fixed set of types. Every method also takes a `FactoryType`
argument that routes the call to one of two backends:

- **`FactoryType.CACHE`** - acts purely on this factory's in-memory registry.
- **`FactoryType.DATABASE`** - acts on the `DatabaseProvider` supplied when the factory was
  constructed (`new DefaultEntityFactory(databaseProvider)`); throws
  `UnsupportedOperationException` if the factory was constructed without one
  (`new DefaultEntityFactory()`).

Entities must extend
[`Serialized`](database-driver-api/src/main/java/de/lino/database/database/entity/Serialized.java),
which supplies `keysOf()`/`primaryKey()`/`hasKey(String)` for lookups. For `FactoryType.DATABASE`,
each entity's fully qualified class name is persisted alongside its JSON payload, so `findEntity`
and `getEntities` can reconstruct it generically on read - unlike `DatabaseSection` itself
(see [Working with a `DatabaseSection`](#databasedriver-api) above), no `Class` token needs to be
passed in by the caller.

```java
import de.lino.database.database.factory.DefaultEntityFactory;
import de.lino.database.database.factory.EntityFactory;
import de.lino.database.database.factory.FactoryType;

// Any application-defined enum works as the grouping tag - no shared interface needed.
enum MyEntityType {
    EXAMS
}

// Cache-only: every FactoryType.DATABASE-routed call throws UnsupportedOperationException.
final EntityFactory cacheOnly = new DefaultEntityFactory();

// Backed by a DatabaseProvider (see "Working with the DatabaseRepository" above) - supports both.
final EntityFactory entityFactory = new DefaultEntityFactory(databaseProvider);

final Exam exam = new Exam("Datenbanksysteme", "2.3"); // any Serialized subclass

// Register in-memory only.
entityFactory.registerEntities(FactoryType.CACHE, MyEntityType.EXAMS, exam);

// Persist instead - the database section is named after MyEntityType.EXAMS.name(), i.e. "EXAMS".
entityFactory.registerEntities(FactoryType.DATABASE, MyEntityType.EXAMS, exam);

// Look an entity up by any of its Serialized#keysOf() values, e.g. its primary key.
final Optional<Exam> cachedExam    = entityFactory.findEntity(FactoryType.CACHE, MyEntityType.EXAMS, "Datenbanksysteme");
final Optional<Exam> persistedExam = entityFactory.findEntity(FactoryType.DATABASE, MyEntityType.EXAMS, "Datenbanksysteme");

// Read every entity registered under a tag.
final List<Exam> cachedExams    = entityFactory.getEntities(FactoryType.CACHE, MyEntityType.EXAMS);
final List<Exam> persistedExams = entityFactory.getEntities(FactoryType.DATABASE, MyEntityType.EXAMS);

// Remove entities again; only the ones actually found (and thus removed) are returned.
final List<Exam> removedFromCache    = entityFactory.unregisterEntities(FactoryType.CACHE, MyEntityType.EXAMS, exam);
final List<Exam> removedFromDatabase = entityFactory.unregisterEntities(FactoryType.DATABASE, MyEntityType.EXAMS, exam);
```

--- ---

## AI-Assisted Development

This project uses [Claude Code](https://claude.com/claude-code) (Anthropic's AI coding
assistant) as a collaborative tool for parts of its development, alongside manual work by the
maintainer. This section exists for transparency, not as a completeness guarantee — AI
involvement isn't tracked on every commit, so what follows is a lower bound on where it was
used, not an exhaustive log.

**What Claude has contributed to**, to the best of what can be verified from this repository's
history and the sessions that produced these changes:
- The generic [`EntityFactory`/`FactoryType`/`DefaultEntityFactory`](#using-entityfactory)
  entity registry — API design across several iterations, implementation, and its
  documentation in this README.
- `PageLayout`/`PageOrientation` page-format support for `TranscriptExporter`
  ([`2608212`](https://github.com/linoalessio/database-driver-v2/commit/2608212e8fb919534f9ae6efec324866f1b99e70)).
- Assorted smaller changes across the codebase: bug fixes, refactors, Javadoc passes, and
  README updates, including this section.

**How to verify it yourself:** commits explicitly co-authored by Claude carry a
`Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` (or similar) trailer:

```bash
git log --all --grep="Co-Authored-By: Claude"
```

Not every AI-assisted change is marked this way — some, including part of the `EntityFactory`
work above, were committed without the trailer — so this search under-counts rather than
over-counts.

**What stays human:** every AI-proposed change in this project is reviewed, tested and
committed by the maintainer. Claude does not push, merge or cut a release on its own behalf.

## License

This project is distributed under the terms found in [LICENSE.txt](LICENSE.txt).
