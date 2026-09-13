"""Mirror of ``de.lino.database.database.DatabaseType``."""

from __future__ import annotations

from enum import Enum


class DatabaseType(Enum):
    """Enumerates every database backend supported by the driver, together with the
    metadata required to connect to it: its short type identifier (used e.g. to build
    connection URLs) and the Python driver package the plugin module connects through.

    The constant set deliberately matches the Java edition one-for-one so configuration
    (and persisted ``Credentials`` files) stay portable between the two. Two constants -
    :attr:`H2_DB` and :attr:`APACHE_DERBY` - are *embedded JVM* databases with no Python
    driver; they are kept for contract parity, but the plugin module raises on any attempt
    to register a provider for them. ``driver_package`` is this edition's counterpart of
    the Java enum's JDBC ``driverClass``: the import name of the Python driver, or
    ``"NULL"`` where none is involved.
    """

    MY_SQL = ("mysql", "pymysql")
    """MySQL, accessed through the PyMySQL driver."""

    POSTGRES_SQL = ("postgresql", "psycopg")
    """PostgreSQL, accessed through the psycopg (v3) driver."""

    H2_DB = ("h2", "NULL")
    """H2 - an embedded JVM database with no Python driver; unsupported by the Python
    plugin module, present only for contract parity with the Java edition."""

    MONGO_DB = ("mongo", "pymongo")
    """MongoDB, accessed through the PyMongo driver."""

    RETHINK_DB = ("rethink", "rethinkdb")
    """RethinkDB, accessed through the rethinkdb driver."""

    JSON = ("json", "NULL")
    """A local, file-based JSON store; not backed by any network driver."""

    CSV = ("csv", "NULL")
    """A local, file-based CSV store; not backed by any network driver."""

    TOML = ("toml", "NULL")
    """A local, file-based TOML store (one directory per section, one ``<id>.toml`` file
    per entry); not backed by any network driver. Same layout as the JSON store, but the
    files are TOML - readable and hand-editable configuration syntax - at the price of
    TOML's model limits (no ``null`` values, homogeneous arrays only; see the store's own
    documentation)."""

    MARIA_DB = ("mariadb", "pymysql")
    """MariaDB, accessed through the PyMySQL driver (protocol-compatible with MySQL)."""

    SQLITE = ("sqlite", "sqlite3")
    """SQLite, accessed through the stdlib :mod:`sqlite3` module - the one backend whose
    driver ships with Python itself."""

    ORACLE = ("oracle:thin", "oracledb")
    """Oracle Database, accessed through the python-oracledb thin driver."""

    MICROSOFT_SQL_SERVER = ("sqlserver", "pymssql")
    """Microsoft SQL Server, accessed through the pymssql driver."""

    APACHE_DERBY = ("derby:memory", "NULL")
    """Apache Derby - an embedded JVM database with no Python driver; unsupported by the
    Python plugin module, present only for contract parity with the Java edition."""

    REDIS = ("redis", "redis")
    """Redis, accessed through the redis-py client."""

    def __init__(self, type: str, driver_package: str) -> None:
        self.type = type
        self.driver_package = driver_package
