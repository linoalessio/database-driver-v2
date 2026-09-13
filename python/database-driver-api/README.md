# lino-database-driver-api

The Python mirror of [`de.lino.database:database-driver-api`](../../database-driver-api) —
the public contracts of the DatabaseDriver library, ported package-for-package from the
Java module. Every Java class maps to one Python module at the same package path
(`de.lino.database.database.DatabaseSection` → `database_driver.api.database.database_section`).

This distribution ships **contracts only** (plus the `JsonDocument` model and the
self-seeding `Credentials` config): `DatabaseRepository`, `DatabaseProvider`,
`DatabaseSection`, `DatabaseEntry`, `Serialized`, the `Cache`/`ClusteredCache` contracts,
and the `DataExporter`/`TranscriptExporter`/`ArchiveExporter`/`ExporterInjector` export
contracts. It has **zero runtime dependencies**. The concrete backends, caches and the
export coordinator live in the sibling `lino-database-driver-plugin` distribution, which
installs into the same `database_driver` namespace — the same api/plugin split as the two
Maven artifacts.

```python
from database_driver.api import DatabaseSection, JsonDocument, SectionConfig
```

## Sync + async

Every synchronous operation has a corresponding `*_async` coroutine method
(`insert`/`insert_async`, `count`/`count_async`, …), mirroring the Java edition's
`CompletableFuture`-based `*Async` defaults. The default `*_async` implementations
offload the blocking call via `asyncio.to_thread`, exactly as the Java defaults offload
to the common pool; plugin implementations backed by natively async drivers (asyncpg,
motor, redis.asyncio) may override them with genuinely non-blocking I/O.

## Deviations from the Java edition

Deliberate, and limited to language-boundary items:

- `Optional<T>` → `T | None`; `Pair` stays a named type for `convert()`'s result.
- Java overload sets fold into one method with dispatch or optional parameters
  (`createSection(String)`/`createSection(String, SectionConfig)` →
  `create_section(name, config=None)`; `DatabaseSection.getEntries(long, int)` →
  `get_entries_page(offset, limit)` since Python cannot overload by arity).
- `ServiceLoader` discovery of the `CacheProvider` → the
  `database_driver.cache_provider` entry-point group.
- The functional interfaces (`DataExporter`, `ArchiveExporter`, `TranscriptExporter`)
  are structural `Protocol`s, so any object with a matching `export` method satisfies
  them — the analogue of satisfying a `@FunctionalInterface` with a lambda.
- `DatabaseType` keeps all 14 constants for config parity, but `H2_DB` and
  `APACHE_DERBY` are embedded JVM databases with no Python driver; the plugin module
  rejects them at registration.

Persisted formats (`Credentials` JSON, entry documents) are byte-compatible between the
two editions, so the same on-disk data can be read from Java and Python.

## Development

```bash
pip install -e .[dev]
pytest
```
