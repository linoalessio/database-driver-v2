package de.lino.database.database.sql.derby;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for Apache Derby, connected via {@link SQLExecution} using
 * {@link DatabaseType#APACHE_DERBY}'s JDBC driver and URL scheme.
 */
@Getter
public class ApacheDerbyDatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to an Apache Derby database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public ApacheDerbyDatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.APACHE_DERBY, new SQLExecution(DatabaseType.APACHE_DERBY, credentials));
    }
    
}
