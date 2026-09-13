# Configuration

This page covers everything the driver reads as configuration: the `Credentials` config file
(the only configuration file the library has), which constructor to use per backend, and how
secrets are handled. For building and running the project, see
[getting-started.md](getting-started.md); for the in-code per-section cache configuration
(`SectionConfig`), see [api-reference.md](api-reference.md#sectionconfig-and-cachemode).

The driver reads **no environment variables** and has no global config file of its own. All
configuration is passed programmatically — connection details through a `Credentials` object,
cache behavior through `SectionConfig` — and only `Credentials` touches disk.

| Source | Holds |
|---|---|
| `Credentials` config file (JSON, path chosen by the caller) | Connection details for one registered provider: host, user, password, port, database name, file-store directory |
| `SectionConfig` (in code, no file) | Per-section cache mode — see [api-reference.md](api-reference.md#sectionconfig-and-cachemode) |

---

## The `Credentials` config file

`Credentials` is a **self-seeding config file, not a plain value object**:

- On the **first** construction for a given `configDestination` path, the constructor arguments
  are written to that path as pretty-printed JSON — and used for the connection.
- On **every subsequent** construction for the same path, the existing file is read back and
  **all constructor arguments other than `configDestination` are ignored**. The file, not the
  caller, is the source of truth after the first run. Changing connection details therefore
  means editing (or deleting) the file, not changing the code.
- If reading an existing file fails, the error is printed and the instance is left with unset
  fields — the failure surfaces later, when the provider tries to connect, not at construction.

`Credentials.of(path)` reads a config file without ever writing one; it returns an empty
`Optional` (Java) / `None` (Python) when the file does not exist or cannot be parsed.

Both editions write the same JSON shape with the same camelCase keys, so a config file written
by one edition is readable by the other.

### File keys

| Key | Type | Written as | Used by |
|---|---|---|---|
| `address` | string | `"Unknown"` placeholder unless a network constructor was used | network backends (host name) |
| `userName` | string | `"Unknown"` placeholder unless set | network backends |
| `password` | string **(secret, stored in plaintext)** | `"Unknown"` placeholder unless set | network backends |
| `port` | number | `-1` unless set | network backends |
| `database` | string | `"Unknown"` placeholder unless set | network backends (database/schema name; for `REDIS` the numeric database index, as a string) |
| `fileRepository` | string | `"Unknown"` unless a file-based constructor was used | `SQLITE`, `H2_DB`, `JSON`, `TOML`, `CSV` |

There is no key for the backend type: the same file works for any `DatabaseType`, and the type
is chosen at `registerDatabaseProvider` time.

### Secrets handling

The password is persisted **as a plain JSON string — no hashing, no encryption**. Treat every
`Credentials` config file as a secret:

- Keep config files out of version control (add the config directory to `.gitignore`).
- Point `configDestination` at a location with appropriately restricted filesystem permissions.
- Never commit a seeded config file as an "example" — seed a fresh one with placeholder values
  instead.

The driver itself never logs credential values.

---

## Which constructor for which backend

Three constructors exist; pick by backend kind. All paths below are chosen by you.

**Network backends** — `MY_SQL`, `MARIA_DB`, `POSTGRES_SQL`, `ORACLE`,
`MICROSOFT_SQL_SERVER`, `MONGO_DB`, `RETHINK_DB`, `REDIS` (and `APACHE_DERBY`, see note):

```java
new Credentials(Paths.get("<CONFIG_PATH>"), "<HOST>", "<USER>", "<DB_PASSWORD>", <PORT>, "<DATABASE>");
```

**File-based backends** — `SQLITE`, `H2_DB`, `JSON`, `TOML`, `CSV`:

```java
new Credentials(Paths.get("<CONFIG_PATH>"), Paths.get("<REPOSITORY_PATH>"));
```

There is also a full seven-argument constructor taking both the network fields and the file
repository; the two shorter forms fill the unused fields with `"Unknown"` placeholders.

Backend-specific meanings of the second argument:

| Backend | `fileRepository` means | Notes |
|---|---|---|
| `SQLITE` | database file path **without extension** | the driver appends `.sqlite` itself |
| `H2_DB` | database file path (H2 adds its own suffixes) | Java edition only |
| `JSON`, `TOML` | the directory holding one subdirectory per section | JSON and TOML use the **same** directory-per-section layout and cannot tell each other's sections apart — give each provider its own directory |
| `CSV` | the directory holding one `.csv` file per section | |

Further notes:

- `APACHE_DERBY` (Java edition only) runs embedded and in-memory
  (`jdbc:derby:memory:<database>;create=true`): only the `database` field is used; `address`
  and `port` are accepted but ignored.
- For `REDIS`, `database` is the numeric Redis database index. When both `userName` and
  `password` are empty strings, the driver connects without authentication and selects the
  database index directly; otherwise it connects via a `redis://` URI with the password.
- `H2_DB` and `APACHE_DERBY` are Java-only: the Python edition raises `NotImplementedError`
  for both (embedded JVM databases have no wire protocol a Python driver could speak).

The Python constructor takes the same values as keyword arguments
(`Credentials(config_destination, address=..., user_name=..., password=..., port=...,
database=..., file_repository=...)`); file-based backends pass only `config_destination` and
`file_repository`.

---

## Notes and foot-guns

- **The seed-once semantics are the classic surprise**: passing new connection details to a
  constructor whose config file already exists silently keeps the old details. Delete or edit
  the file to change them.
- The config file is written with placeholder values (`"Unknown"`, `-1`) for whichever fields
  the chosen constructor did not set. A file full of `"Unknown"` is normal for file-based
  backends.
- One config file describes one provider. Registering several providers means several
  `Credentials`, each with its own `configDestination`.
- JDBC drivers for `MY_SQL`, `ORACLE`, `MICROSOFT_SQL_SERVER` and `APACHE_DERBY` are **not
  bundled** with the Java plugin module — see
  [getting-started.md](getting-started.md#drivers-that-are-not-bundled). In the Python edition
  every network backend's driver is an opt-in pip extra.
