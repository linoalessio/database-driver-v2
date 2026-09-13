"""Mirror of ``de.lino.database.database.sql.microsoft.MicrosoftSQLServerDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType
from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class MicrosoftSQLServerDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for Microsoft SQL Server, connected via ``SQLExecution`` using
    ``DatabaseType.MICROSOFT_SQL_SERVER``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.MICROSOFT_SQL_SERVER, SQLExecution(DatabaseType.MICROSOFT_SQL_SERVER, credentials))
