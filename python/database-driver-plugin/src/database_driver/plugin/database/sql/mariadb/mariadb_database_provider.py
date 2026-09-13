"""Mirror of ``de.lino.database.database.sql.mariadb.MariaDBDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.database_type import DatabaseType
from database_driver.plugin.database.sql.sql_database_provider import SQLDatabaseProvider
from database_driver.plugin.database.sql.sql_execution import SQLExecution


class MariaDBDatabaseProvider(SQLDatabaseProvider):
    """The ``SQLDatabaseProvider`` for MariaDB, connected via ``SQLExecution`` using
    ``DatabaseType.MARIA_DB``'s driver and connection scheme."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__(DatabaseType.MARIA_DB, SQLExecution(DatabaseType.MARIA_DB, credentials))
