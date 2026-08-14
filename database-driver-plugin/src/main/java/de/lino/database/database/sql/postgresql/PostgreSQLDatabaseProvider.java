package de.lino.database.database.sql.postgresql;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for PostgreSQL, connected via {@link SQLExecution} using
 * {@link DatabaseType#POSTGRES_SQL}'s JDBC driver and URL scheme.
 */
@Getter
public class PostgreSQLDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to a PostgreSQL database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public PostgreSQLDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.POSTGRES_SQL, new SQLExecution(DatabaseType.POSTGRES_SQL, credentials));
    }
    
}
