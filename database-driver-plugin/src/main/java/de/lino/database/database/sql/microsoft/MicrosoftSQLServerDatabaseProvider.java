package de.lino.database.database.sql.microsoft;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for Microsoft SQL Server, connected via {@link SQLExecution}
 * using {@link DatabaseType#MICROSOFT_SQL_SERVER}'s JDBC driver and URL scheme.
 */
@Getter
public class MicrosoftSQLServerDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to a Microsoft SQL Server database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public MicrosoftSQLServerDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.MICROSOFT_SQL_SERVER, new SQLExecution(DatabaseType.MICROSOFT_SQL_SERVER, credentials));
    }
    
}
