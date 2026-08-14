package de.lino.database.database.sql.orcale;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for Oracle Database, connected via {@link SQLExecution} using
 * {@link DatabaseType#ORACLE}'s JDBC driver and URL scheme.
 */
@Getter
public class OracleSQLDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to an Oracle database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public OracleSQLDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.ORACLE, new SQLExecution(DatabaseType.ORACLE, credentials));
    }
    
}
