package de.lino.database.provider.nosql.mongodb;

/*
 * MIT License
 *
 * Copyright (c) lino, 08.09.2025
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

import com.google.common.collect.Lists;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Getter
public class MongoDBDatabaseSection implements DatabaseSection {

    private final String name;
    private final List<DatabaseEntry> entries;

    private final MongoCollection<Document> collection;

    public MongoDBDatabaseSection(@NotNull MongoDatabase mongoDatabase, @NotNull String name) {

        this.name = name;
        this.entries = Lists.newCopyOnWriteArrayList();

        this.collection = mongoDatabase.getCollection(name);
        this.collection.find().forEach((Consumer<Document>) document ->
                this.entries.add(new DatabaseEntry(document.getString("id"), new JsonDocument(document.toJson()))));

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.exists(databaseEntry.getId())) return;
        final String json = new JsonDocument().append("id", databaseEntry.getId()).append(databaseEntry.getMetaData()).toJson();

        this.collection.insertOne(new JsonDocument().getGson().fromJson(json, Document.class));
        this.entries.add(databaseEntry);

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) return;

        final String json = new JsonDocument().append("id", databaseEntry.getId()).append(databaseEntry.getMetaData()).toJson();
        this.collection.replaceOne(Filters.eq("id", databaseEntry.getId()), new JsonDocument().getGson().fromJson(json, Document.class));

        this.entries.remove(databaseEntry);
        this.entries.add(databaseEntry);

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) return;
        if (this.collection.deleteOne(Filters.eq("id", id)).getDeletedCount() > 0) return;

        this.collection.deleteOne(Filters.eq("id", id));
        this.entries.removeIf(databaseEntity -> databaseEntity.getId().equals(id));

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void delete() {
        this.collection.drop();
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.stream().anyMatch(databaseEntity -> databaseEntity.getId().equals(id));
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.stream().filter(databaseEntity -> databaseEntity.getId().equals(id)).findFirst().orElse(null));
    }

}
