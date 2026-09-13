# Getting Started

This page covers building both editions locally from a fresh clone, and consuming the
published artifacts from your own project. For connection configuration see
[configuration.md](configuration.md); for worked API examples see
[api-usage.md](api-usage.md); for how the parts fit together see
[architecture.md](architecture.md).

The two editions are independent: the Java toolchain is not needed to use the Python mirror,
and vice versa. Build order only matters *within* an edition.

## Prerequisites

| Requirement | Needed for |
|---|---|
| **JDK 21** — exactly 21 | Building either Maven module. Both hardcode `source`/`target` 21, and Lombok's annotation processor does not run under newer JDKs on this project |
| Maven 3.x | Building the Java edition; no wrapper is committed, so use a local install |
| **Python 3.11+** (3.11, 3.12 and 3.13 are tested in CI) | The Python edition |
| A GitHub personal access token with `read:packages` | Only to consume the published Maven artifacts — not needed to build from source |
| A database server | Only for the network backends you actually use. SQLite, H2 and the JSON/TOML/CSV file stores need none |

On macOS with several JDKs installed, pin `JAVA_HOME` before invoking Maven:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
```

## 1. Clone

```bash
git clone https://github.com/linoalessio/database-driver-v2.git
cd database-driver-v2
```

## 2. Build the Java edition

```bash
mvn clean verify
```

This builds both modules in dependency order (`database-driver-api`, then
`database-driver-plugin`) and runs the JUnit 5 suite. Other useful invocations:

```bash
mvn -pl database-driver-api,database-driver-plugin -am compile   # compile only
mvn -pl database-driver-plugin -am install -DskipTests           # install locally for a consumer project
```

`mvn install` puts both artifacts in your local `~/.m2` repository, which is the simplest way
to try changes in a consuming project without publishing anything.

## 3. Build the Python edition

Each package under `python/` is its own project. The plugin depends on the api package, which
is published to no index — so both paths must go into **one** pip invocation, otherwise the
second install re-resolves the plugin's requirements from scratch and fails:

```bash
pip install -e ./python/database-driver-api -e "./python/database-driver-plugin[dev]"
```

Then run the checks the CI matrix runs, per package:

```bash
cd python/database-driver-api
ruff check src tests
mypy
pytest
```

```bash
cd python/database-driver-plugin
ruff check src tests
mypy
pytest
```

Network backends and the office-format exporters are opt-in extras — `postgres`, `mysql`,
`mssql`, `oracle`, `mongodb`, `redis`, `rethinkdb`, `export`, and `all`:

```bash
pip install -e ./python/database-driver-api -e "./python/database-driver-plugin[postgres,export]"
```

Without any extra you still get the JSON/TOML/CSV file stores, SQLite, both cache
implementations and the stdlib transcript exporters (CSV/XML/JSON) plus ZIP archives.

## 4. Verifying it works

The test suites are the smoke test for both editions — they exercise every cache mode against
the JSON file store and SQLite, plus streaming, paging, stats and the TOML store, all without
any database server:

```bash
mvn -pl database-driver-plugin -am test        # Java: 6 JUnit 5 classes
```

```bash
cd python/database-driver-plugin && pytest -q  # Python: the plugin suite
```

For a round trip in your own code, the server-less JSON store is the quickest path — see the
[core CRUD example](api-usage.md#1-java-the-core-crud-path). Success looks like
`findEntryById` returning the document you inserted, and a `data/players/Lino.json` file
appearing under the directory you passed as the file repository.

## Consuming the published artifacts

### Java (Maven)

Artifacts are published to **GitHub Packages**, not Maven Central, so two extra steps are
needed before the dependencies resolve.

**1. Point Maven at the registry** in your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub LinoAlessio Apache Maven Packages</name>
    <url>https://maven.pkg.github.com/linoalessio/database-driver-v2</url>
  </repository>
</repositories>
```

**2. Authenticate.** GitHub Packages requires an authenticated request for every download,
including from a public repository. Create a personal access token with the `read:packages`
scope and add a matching server entry to `~/.m2/settings.xml` — reference an environment
variable rather than hardcoding the token:

```xml
<settings>
  <servers>
    <server>
      <id>github</id> <!-- must match the <id> of the <repository> above -->
      <username><GITHUB_USERNAME></username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

```bash
export GITHUB_TOKEN=<YOUR_READ_PACKAGES_TOKEN>
```

**3. Declare the dependencies.** Compile against `-api`; `-plugin` must be on the runtime
classpath for a working provider:

```xml
<dependencies>
  <dependency>
    <groupId>de.lino.database</groupId>
    <artifactId>database-driver-api</artifactId>
    <version><VERSION></version>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>de.lino.database</groupId>
    <artifactId>database-driver-plugin</artifactId>
    <version><VERSION></version>
  </dependency>
</dependencies>
```

Replace `<VERSION>` with the version you want; the current one is shown by the version badge
in the [root README](../README.md).

### Python

The Python packages are **not published to any package index yet**. Install them from a clone,
as in [step 3](#3-build-the-python-edition) (drop `-e` for a non-editable install).

## Drivers that are not bundled

`database-driver-plugin` bundles JDBC drivers for PostgreSQL, H2, SQLite and MariaDB, the
MongoDB, RethinkDB and Jedis clients, and the toml4j parser. It does **not** bundle drivers
for **MySQL**, **Oracle**, **Microsoft SQL Server** or **Apache Derby** — add the
corresponding JDBC driver to your own project if you use one of those. In the Python edition
every network driver is an opt-in extra instead; see
[api-reference.md](api-reference.md#databasetype) for the per-backend table.

## Next steps

- [api-usage.md](api-usage.md) — worked examples for every surface
- [configuration.md](configuration.md) — connection details, secrets, per-backend constructors
- [contributing.md](contributing.md) — module boundaries and conventions before your first change
- [testing.md](testing.md) — what is verified, and how
