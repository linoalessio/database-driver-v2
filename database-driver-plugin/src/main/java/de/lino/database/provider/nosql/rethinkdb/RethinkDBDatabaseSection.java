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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.ast.Db;
import com.rethinkdb.gen.ast.Table;
import com.rethinkdb.model.MapObject;
import com.rethinkdb.net.Connection;
import com.rethinkdb.net.Result;
import com.rethinkdb.utils.Types;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.exception.EntryAlreadyInserted;
import de.lino.database.exception.NoSuchDataFound;
import de.lino.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Getter
public class RethinkDBDatabaseSection implements DatabaseSection {

    private final String name;

    private final Map<String, DatabaseEntry> entries;

    private final TypeReference<Map<String, String>> cache;
    private final Connection connection;
    private final Table table;

    public RethinkDBDatabaseSection(@NotNull String name, @NotNull Connection connection, @NotNull Db db) {

        this.name = name;
        this.entries = Maps.newConcurrentMap();

        this.connection = connection;
        this.cache = Types.mapOf(String.class, String.class);
        this.table = db.table(name);

        try (final Result<Map<String, String>> result = this.table.run(this.connection, this.cache)) {

            while (result.hasNext()) {

                final Map<String, String> content = result.next();
                if (!content.containsKey("data")) throw new NoSuchDataFound(content.get("id"));
                this.entries.put(content.get("id"), new DatabaseEntry(Objects.requireNonNull(content).get("id"), new JsonDocument(content.get("values"))));

            }

        }

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.exists(databaseEntry.getId())) throw new EntryAlreadyInserted(databaseEntry.getId());

        this.table.insert(this.mapping(databaseEntry)).runNoReply(this.connection);
        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());
        this.table.update(this.mapping(databaseEntry)).runNoReply(this.connection);

        this.entries.remove(databaseEntry.getId());
        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        this.table.filter(this.mapping(id)).delete().runNoReply(this.connection);
        this.entries.remove(id);

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {
        this.table.delete().runNoReply(this.connection);
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.containsKey(id);
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public @UnmodifiableView List<DatabaseEntry> getEntries() {
        return Lists.newCopyOnWriteArrayList(this.entries.values());
    }

    private MapObject<Object, Object> mapping(@NotNull String id) {
        return RethinkDB.r.hashMap("id", id);
    }

    private Map<Object, Object> mapping(@NotNull DatabaseEntry databaseEntry) {
        return this.mapping(databaseEntry.getId()).with("values", databaseEntry.getDocument().toString());
    }

}
