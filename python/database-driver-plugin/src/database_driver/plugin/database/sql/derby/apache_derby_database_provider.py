"""Mirror of ``de.lino.database.database.sql.derby.ApacheDerbyDatabaseProvider``."""

from __future__ import annotations

from database_driver.api.database.auth.credentials import Credentials


class ApacheDerbyDatabaseProvider:
    """Present for contract parity with the Java edition, and nothing more: Apache Derby
    is an *embedded JVM* database - a Java library, not a wire protocol - so no Python
    driver for it exists or can exist without embedding a JVM. Constructing this always
    raises; the SQLite backend covers the embedded, file-based use case in this
    edition."""

    def __init__(self, credentials: Credentials) -> None:
        raise NotImplementedError(
            "@ApacheDerbyDatabaseProvider: Apache Derby is an embedded JVM database with no Python driver; "
            "use DatabaseType.SQLITE for an embedded, file-based store"
        )
