"""Mirror of ``de.lino.database.database.sql.orcale.OracleSQLDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType
from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class OracleSQLDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for Oracle Database (thin mode), connected via ``SQLExecution`` using
    ``DatabaseType.ORACLE``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.ORACLE, SQLExecution(DatabaseType.ORACLE, credentials))
