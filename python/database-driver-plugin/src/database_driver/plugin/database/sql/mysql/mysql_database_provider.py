"""Mirror of ``de.lino.database.database.sql.mysql.MySQLDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType
from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class MySQLDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for MySQL, connected via ``SQLExecution`` using
    ``DatabaseType.MY_SQL``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.MY_SQL, SQLExecution(DatabaseType.MY_SQL, credentials))
