package de.lino.database.database.sql.sqlite;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for SQLite, connected via {@link SQLExecution} using
 * {@link DatabaseType#SQLITE}'s JDBC driver and file-based URL scheme.
 */
@Getter
public class SQLiteDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to a SQLite database file with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public SQLiteDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.SQLITE, new SQLExecution(DatabaseType.SQLITE, credentials));
    }
    
}
