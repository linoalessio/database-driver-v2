package de.lino.database.database.nosql.rethinkdb;

import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.ast.Db;
import com.rethinkdb.net.Connection;
import com.rethinkdb.net.Result;
import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The {@link DatabaseProvider} backed by a RethinkDB database, each {@link DatabaseSection} a
 * table via {@link RethinkDBDatabaseSection}, all sharing this database's single
 * {@link Connection}. Section lifecycle and caching live in {@link AbstractLazyDatabaseProvider};
 * this class only supplies the table-level storage operations - listing tables, constructing a
 * {@link RethinkDBDatabaseSection}, dropping a table. RethinkDB's {@link Connection} multiplexes
 * concurrent queries over one underlying socket and is itself thread-safe, so every method here
 * is safe to call concurrently without additional locking.
 */
@Getter
public class RethinkDBDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * The connection shared by this database and every {@link RethinkDBDatabaseSection} it creates.
     */
    private final Connection connection;

    /**
     * The database this database is connected to.
     */
    private final Db db;

    /**
     * Connects to a RethinkDB database with {@code credentials} and discovers every existing
     * table's name. Only names - no section objects, no rows - so construction cost is one
     * {@code tableList} query, independent of how much the database holds.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public RethinkDBDatabaseProvider(@NotNull Credentials credentials) {

        this.connection = RethinkDB.r.connection()
                .hostname(credentials.getAddress())
                .port(credentials.getPort())
                .user(credentials.getUserName(), credentials.getPassword())
                .db(credentials.getDatabase())
                .connect();
        this.db = RethinkDB.r.db(credentials.getDatabase());

        this.reload();

    }

    @Override
    public void shutdown() {
        this.connection.close();
        this.forgetSections();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Runs the database's {@code tableList} query, streaming each table name to
     * {@code consumer}.
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        try (final Result<String> names = this.db.tableList().run(this.connection, String.class)) {
            names.forEach(consumer);
        }

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new RethinkDBDatabaseSection(name, this.connection, this.db, config);
    }

    @Override
    protected void dropSectionRemote(@NotNull String name) {
        this.db.tableDrop(name).run(this.connection);
    }

}
