package de.lino.database.database.nosql.rethinkdb;

import com.google.common.collect.Maps;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.ast.Db;
import com.rethinkdb.net.Connection;
import com.rethinkdb.net.Result;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseProvider} backed by a RethinkDB database, each {@link DatabaseSection} a
 * table via {@link RethinkDBDatabaseSection}, all sharing this database's single
 * {@link Connection}. RethinkDB's {@link Connection} multiplexes concurrent queries over one
 * underlying socket and is itself thread-safe, so every method here is safe to call
 * concurrently without additional locking.
 */
@Getter
public class RethinkDBDatabaseProvider implements DatabaseProvider {

    /**
     * Every registered section, keyed by table name.
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * The connection shared by this database and every {@link RethinkDBDatabaseSection} it creates.
     */
    private final Connection connection;

    /**
     * The database this database is connected to.
     */
    private final Db db;

    /**
     * Connects to a RethinkDB database with {@code credentials} and loads every existing table
     * as a {@link RethinkDBDatabaseSection}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public RethinkDBDatabaseProvider(@NotNull Credentials credentials) {

        this.databaseSections = Maps.newConcurrentMap();

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
        this.databaseSections.clear();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #databaseSections} entirely and rebuilds it with a fresh
     * {@link RethinkDBDatabaseSection} per table currently in {@link #db}, the same
     * scan the constructor itself runs.
     */
    @Override
    public void reload() {

        this.databaseSections.clear();

        try (final Result<String> names = this.db.tableList().run(this.connection, String.class)) {

            names.forEach(name ->
                    this.databaseSections.put(name, new RethinkDBDatabaseSection(name, this.connection, this.db)));

        }

    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new RethinkDBDatabaseSection(key, this.connection, this.db));
    }

    @Override
    public void deleteSection(@NotNull String name) {
        this.db.tableDrop(name).run(this.connection);
        this.databaseSections.remove(name);
    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return List.copyOf(this.databaseSections.values());
    }

    @Override
    public Optional<DatabaseSection> getSection(@NotNull String name) {
        return Optional.ofNullable(this.databaseSections.get(name));
    }

    @Override
    public void clear() {
        for (DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.databaseSections.clear();
    }

}
