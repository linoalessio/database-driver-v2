package de.lino.database.database.nosql.mongodb;

import com.google.common.collect.Maps;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseSection} backing one MongoDB collection. Entries are cached in memory
 * (loaded once in the constructor and kept in sync on every write) so reads never touch the
 * database, only writes do.
 */
@Getter
public class MongoDBDatabaseSection implements DatabaseSection {

    /**
     * This section's collection name.
     */
    private final String name;

    /**
     * Every entry currently in {@link #collection}, keyed by id and kept in sync with the
     * database by every write method; the source of truth for every read method.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * The collection this section wraps.
     */
    private final MongoCollection<Document> collection;

    /**
     * Loads {@code name}'s existing documents into {@link #entries}.
     *
     * @param mongoDatabase the database {@code name}'s collection belongs to
     * @param name          this section's collection name
     */
    public MongoDBDatabaseSection(@NotNull MongoDatabase mongoDatabase, @NotNull String name) {

        this.name = name;
        this.entries = Maps.newConcurrentMap();
        this.collection = mongoDatabase.getCollection(name);

        this.reload();

    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #entries} entirely and re-populates it from every document
     * currently in {@link #collection}, the same scan the constructor itself runs.
     */
    @Override
    public void reload() {

        this.entries.clear();

        for (Document document : this.collection.find()) {

            if (!document.containsKey("data")) throw new NoSuchDataFound(document.getString("id"));

            final JsonDocument jsonDocument = new JsonDocument(document.toJson());
            this.entries.put(document.getString("id"), new DatabaseEntry(document.getString("id"), new JsonDocument("data", jsonDocument.getMetaData("data"))));

        }

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new DataAlreadyExist(databaseEntry.getId());

        // databaseEntry.getDocument() is already the full "data"-enveloped document (see its
        // own javadoc); appending it here as-is under another "data" key would double-wrap it,
        // so its already-unwrapped getMetaData() is used instead, matching update() below.
        final String json = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData()).toJson();
        this.collection.insertOne(new JsonDocument().getGson().fromJson(json, Document.class));

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        final String json = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData()).toJson();
        this.collection.updateOne(Filters.eq("id", databaseEntry.getId()), new Document("$set", new JsonDocument().getGson().fromJson(json, Document.class)));

        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        this.collection.deleteOne(Filters.eq("id", id));
        this.entries.remove(id);

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {
        this.collection.deleteMany(new Document());
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
        return List.copyOf(this.entries.values());
    }

}
