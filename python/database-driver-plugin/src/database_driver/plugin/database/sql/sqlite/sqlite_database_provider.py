"""Mirror of ``de.lino.database.database.sql.sqlite.SQLiteDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType

from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class SQLiteDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for SQLite (file-based), connected via ``SQLExecution`` using
    ``DatabaseType.SQLITE``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.SQLITE, SQLExecution(DatabaseType.SQLITE, credentials))
