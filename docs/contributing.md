# Contributing

This page covers the rules a change to this repository must follow, the recipes for the things
contributors add repeatedly, and the documentation-maintenance contract. For building and
running the checks, see [getting-started.md](getting-started.md); for the reasoning behind the
structure, see [architecture.md](architecture.md).

## Module boundaries

These are prohibitions, not preferences — a change that breaks one is wrong even if it
compiles. The rationale for each is in
[architecture.md](architecture.md#layering-rules).

- **Never add a dependency from `database-driver-api` to `database-driver-plugin`.** The
  dependency direction is `api ← plugin`, one way, in both editions.
- **Never add a third-party database driver to `database-driver-api`.** It carries only Guava,
  Gson, Lombok and the JetBrains annotations; the Python api package carries *zero* runtime
  dependencies and is meant to stay that way.
- **Never let an api-module type reference a plugin type at compile time.** Cross-boundary
  discovery goes through `ServiceLoader` (Java) or entry points (Python).
- **Never reimplement caching inside a backend.** Backends supply storage primitives only; the
  engine owns cache modes, write-through, exceptions, streaming and paging.
- **Keep the two editions in contract parity.** Persisted bytes (entry documents, `Credentials`
  config files, file-store layout) are byte-compatible between Java and Python; a change to one
  edition's persistence format is a breaking change to both. Deliberate divergences are
  allowed, but each one is documented in the code and listed in the
  [README's Python Edition section](../README.md#python-edition).

## Code conventions

| Convention | Detail |
|---|---|
| Javadoc depth | Every public and protected member gets Javadoc explaining *why*, not just what. Match the depth of the existing files — one-line summaries are below the bar |
| Python docstrings | Same bar; modules and public members carry docstrings, and deliberate divergences from the Java edition are explained where they occur |
| Java language level | 21, in both modules; Lombok is used for getters and constructors |
| Package split | API contracts under `de.lino.database.utils.*`, plugin implementations under `de.lino.database.utility.*`. This asymmetry is load-bearing — the `ServiceLoader` resource is named for the api package and contains the plugin class |
| Python layout | One module per Java class, at the identical package path; `snake_case` methods, `*_async` coroutines, `None` instead of `Optional`, keyword arguments instead of overloads |
| Python style | `ruff` with line length 120 (`E`, `F`, `I`, `N`, `UP`, `B`, `RUF`); `N818` is deliberately disabled so exception names can mirror the Java edition's |
| Types | `mypy` is strict for the Python api package and non-strict for the plugin (seven drivers ship no stubs) |
| Exceptions | Absence is expressed by `Optional`/`None` on reads, never by exception; the three domain exceptions are unchecked |

Run the full check set before opening a pull request:

```bash
mvn clean verify
```

```bash
cd python/database-driver-api    && ruff check src tests && mypy && pytest
cd python/database-driver-plugin && ruff check src tests && mypy && pytest
```

## Adding a new backend

1. Decide whether it fits the shared SQL layer. A JDBC vendor usually does: add the
   `DatabaseType` constant (with its URL sub-protocol and driver class), a thin
   `DatabaseProvider` subclass under `database.sql.<vendor>`, and — where the vendor differs —
   a branch in the table-listing query, the blob column type and the paging dialect.
2. A non-SQL backend gets its own `DatabaseProvider`/`DatabaseSection` pair under
   `database.nosql.<backend>`, extending `AbstractLazyDatabaseProvider` and
   `AbstractCachedDatabaseSection`. Implement the three provider primitives (`discoverNames`,
   `constructSection`, `dropSectionRemote`) and the eight section primitives (`loadAll`,
   `fetchOne`, `persistInsert`, `persistUpdate`, `persistDelete`, `countRemote`,
   `existsRemote`, `clearRemote`); override `pageRemote` only if the backend can order and
   slice natively.
3. Register the type in `DatabaseRepositoryRegistry`'s `createProvider` switch.
4. Mirror all of the above in the Python edition, or document why it cannot exist there (as
   `H2_DB` and `APACHE_DERBY` do).
5. Add the driver dependency: a bundled jar in the Java plugin's `pom.xml`, or a new pip extra
   in the Python plugin's `pyproject.toml`.
6. Update the docs: [api-reference.md](api-reference.md#databasetype),
   [architecture.md](architecture.md#backend-storage-mapping), the README's Supported
   Databases table, and [configuration.md](configuration.md) if the backend needs a different
   `Credentials` shape.

## Adding a new export format

1. Add the `ExportType` constant with its file suffix in `database-driver-api`.
2. Add the private nested exporter to `ExportCoordinator` in both editions, and wire it into
   the extension-based resolution.
3. In Python, keep the library import local to the exporter method and add the dependency to
   the `export` extra, so installations without it can still use the stdlib formats.
4. Update [api-reference.md](api-reference.md#export-contracts-and-exportcoordinator) and the
   export example in [api-usage.md](api-usage.md#3-exporting-data).

## Adding a test

Java tests live in `database-driver-plugin/src/test/java/de/lino/database/database/`, Python
tests in each package's `tests/`. New tests must run without a database server — use the JSON,
CSV or TOML file store, or SQLite, as the existing suites do. When you add a Java test for
engine behavior, port it to Python (and vice versa): the suites are deliberately parallel.

## Keeping documentation current

Documentation is part of the change, not a follow-up. When a change lands, the page owning the
affected fact is updated **in the same commit or pull request**:

| When a change… | …update, in the same commit/PR |
|---|---|
| Adds, renames or removes a module or component | README module table + Project Structure, and [architecture.md](architecture.md#components) |
| Adds, changes or removes a public API entry | [api-reference.md](api-reference.md) (+ the [api-usage.md](api-usage.md) example if one exists) |
| Adds a backend or changes how one stores data | [api-reference.md](api-reference.md#databasetype), [architecture.md](architecture.md#backend-storage-mapping), README Supported Databases |
| Changes `Credentials`, its file format, or secret handling | [configuration.md](configuration.md) |
| Changes cache-mode semantics | [api-usage.md](api-usage.md#2-per-section-cache-modes) + [architecture.md](architecture.md#the-engine-one-implementation-of-caching-many-storage-primitives) |
| Changes build, run or test commands | [getting-started.md](getting-started.md) + [testing.md](testing.md) (+ README Installation/Quick Start) |
| Adds, removes or changes a test suite or CI workflow | [testing.md](testing.md) |
| Changes publishing or release mechanics | [deployment.md](deployment.md) |
| Changes a version, count or other fact stated in prose | every stating page — grep the old value before committing |
| Fixes a production issue worth remembering | create `docs/troubleshooting.md` with the first entry |

Standing rules:

- The README is the **map**; a fact's home page is where it changes. The README changes only
  when the *shape* of the project changes.
- **No per-module READMEs.** Module- and class-level detail belongs in Javadoc and docstrings.
- A new `docs/` page requires its row in the README's Documentation table in the same change.
- One home per fact: if you find yourself stating the same value on two pages, link instead.

## Pull requests

- Keep a pull request to one concern; a backend addition, an engine change and a
  documentation restructure are three pull requests.
- State what was verified and how — which suites ran, and which backends were exercised
  manually (the network backends have no automated coverage; see [testing.md](testing.md)).
- Both CI workflows relevant to your change must pass. A change touching both editions builds
  both.
- Never commit credentials, tokens or a seeded `Credentials` config file.
