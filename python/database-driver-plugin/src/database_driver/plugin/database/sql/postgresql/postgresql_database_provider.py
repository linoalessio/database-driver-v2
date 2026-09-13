"""Mirror of ``de.lino.database.database.sql.postgresql.PostgreSQLDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType

from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class PostgreSQLDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for PostgreSQL, connected via ``SQLExecution`` using
    ``DatabaseType.POSTGRES_SQL``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.POSTGRES_SQL, SQLExecution(DatabaseType.POSTGRES_SQL, credentials))
