package de.lino.database.provider.nosql.rethinkdb;

/*
 * MIT License
 *
 * Copyright (c) lino, 09.09.2025
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import com.google.common.collect.Maps;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.ast.Db;
import com.rethinkdb.net.Connection;
import com.rethinkdb.net.Result;
import de.lino.database.configuration.Credentials;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseProvider} backed by a RethinkDB database, each {@link DatabaseSection} a
 * table via {@link RethinkDBDatabaseSection}, all sharing this provider's single
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
     * The connection shared by this provider and every {@link RethinkDBDatabaseSection} it creates.
     */
    private final Connection connection;

    /**
     * The database this provider is connected to.
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

        try (final Result<String> names = this.db.tableList().run(this.connection, String.class)) {

            names.forEach(name ->
                    this.databaseSections.put(name, new RethinkDBDatabaseSection(name, this.connection, this.db)));

        }

    }

    @Override
    public void shutdown() {
        this.connection.close();
        this.databaseSections.clear();
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
