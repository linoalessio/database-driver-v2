# DatabaseDriver — Multiselect Database Management System

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Build](https://img.shields.io/badge/Build-Maven-C71A36)
![Version](https://img.shields.io/badge/Version-1.1.2-blue)

DatabaseDriver is a management system for multiple SQL and NoSQL database types, controlled
through a single, unified interface. Instead of learning a separate API for every backend, you
work against `DatabaseRepository`, `DatabaseProvider`, `DatabaseSection` and `DatabaseEntry` —
the same four abstractions regardless of whether the data actually lives in MySQL, MongoDB, Redis
or a plain directory of JSON files. Every operation is also available in a non-blocking,
`CompletableFuture`-based variant.

## Project Structure

The project is split into two Maven modules:

| **Module**                | **Artifact**            | **Contents**                                                                                                                                                    |
|----------------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `database-driver-api`      | `database-driver-api`    | The public API: `DatabaseRepository`, `DatabaseProvider`, `DatabaseSection`, `DatabaseEntry`, `Credentials`, the JSON document model (`JsonDocument`) and the driver's exceptions. |
| `database-driver-plugin`   | `database-driver-plugin` | The concrete implementation: `DatabaseRepositoryRegistry` and one `DatabaseProvider`/`DatabaseSection` pair per supported database technology.                    |

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
`1.1.2`). `database-driver-api` gives you the interfaces to code against; `database-driver-plugin`
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
* DatabaseType NoSQL: MONGO_DB, RETHINK_DB, JSON, REDIS
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

// Remove every section from this provider
databaseProvider.clear();

// Shut down this database provider
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

// To remove the section itself, delete it through its provider instead:
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

// NoSQL — file-based backend: fileRepository is the *directory* the JSON documents are stored in
final Credentials json = new Credentials(Paths.get("CONFIG_PATH"), Paths.get("DATABASE_REPOSITORY_PATH"));
```

`Credentials` persists whatever you pass in to `configDestination` as JSON the first time it runs;
on every subsequent run it reads the existing file back instead, so the constructor arguments
other than `configDestination` are only used to seed that file once.

--- ---

## License

This project is distributed under the terms found in [LICENSE.txt](LICENSE.txt).
