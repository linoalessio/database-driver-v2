package de.lino.database.database.sql.mariadb;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for MariaDB, connected via {@link SQLExecution} using
 * {@link DatabaseType#MARIA_DB}'s JDBC driver and URL scheme.
 */
@Getter
public class MariaDBDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to a MariaDB database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public MariaDBDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.MARIA_DB, new SQLExecution(DatabaseType.MARIA_DB, credentials));
    }

}
