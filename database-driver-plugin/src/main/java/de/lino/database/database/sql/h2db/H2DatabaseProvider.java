package de.lino.database.database.sql.h2db;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.sql.SQLDatabaseProvider;
import de.lino.database.database.sql.SQLExecution;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link SQLDatabaseProvider} for H2, connected via {@link SQLExecution} using
 * {@link DatabaseType#H2_DB}'s JDBC driver and file-based URL scheme.
 */
@Getter
public class H2DatabaseProvider extends SQLDatabaseProvider {

    /**
     * Connects to an H2 database with {@code credentials}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public H2DatabaseProvider(@NotNull Credentials credentials) {
        super(DatabaseType.H2_DB, new SQLExecution(DatabaseType.H2_DB, credentials));
    }

}
