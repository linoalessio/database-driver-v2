package de.lino.database.database.sql.mysql;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for MySQL, connected via {@link SQLExecution} using
 * {@link DatabaseType#MY_SQL}'s JDBC driver and URL scheme.
 */
@Getter
public class MySQLDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to a MySQL database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public MySQLDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.MY_SQL, new SQLExecution(DatabaseType.MY_SQL, credentials));
    }

}
