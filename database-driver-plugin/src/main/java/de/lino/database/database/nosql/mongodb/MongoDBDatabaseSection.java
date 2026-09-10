package de.lino.database.database.nosql.mongodb;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.CacheMode;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one MongoDB collection. All caching lives in
 * {@link AbstractCachedDatabaseSection}; this class only supplies the collection's storage
 * primitives - each one a single driver call against the {@code {id, data}} document shape.
 */
@Getter
public class MongoDBDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The collection this section wraps.
     */
    private final MongoCollection<Document> collection;

    /**
     * Loads {@code name}'s existing documents into memory immediately - the historical
     * constructor, kept with its exact loaded-once-constructed semantics for anyone
     * instantiating sections directly rather than through a provider.
     *
     * @param mongoDatabase the database {@code name}'s collection belongs to
     * @param name          this section's collection name
     */
    public MongoDBDatabaseSection(@NotNull MongoDatabase mongoDatabase, @NotNull String name) {
        this(mongoDatabase, name, SectionConfig.full());
        this.warmUp();
    }

    /**
     * Prepares the section without reading any document - MongoDB materializes a collection on
     * its first write, so there is no container to create either; whether and when documents
     * are loaded is the engine's decision per {@code config}, with the owning provider
     * triggering the {@link CacheMode#FULL} warm-up right after construction.
     *
     * @param mongoDatabase the database {@code name}'s collection belongs to
     * @param name          this section's collection name
     * @param config        how this section holds entries in memory
     */
    public MongoDBDatabaseSection(@NotNull MongoDatabase mongoDatabase, @NotNull String name, @NotNull SectionConfig config) {

        super(name, config);
        this.collection = mongoDatabase.getCollection(name);

    }

    /**
     * {@inheritDoc}
     * <p>
     * Iterates the collection's {@code find()} cursor, which the driver batches server-side -
     * the collection is never materialized as a whole on this side of the wire.
     */
    @Override
    protected void loadAll(@NotNull Consumer<DatabaseEntry> consumer) {

        for (Document document : this.collection.find()) {
            consumer.accept(this.readEntry(document));
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single filtered {@code find} on the {@code id} field - the same field every write here
     * keys on.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull String id) {

        final Document document = this.collection.find(Filters.eq("id", id)).first();
        return document == null ? Optional.empty() : Optional.of(this.readEntry(document));

    }

    /**
     * Parses one stored document back into a {@link DatabaseEntry}, the shared row shape
     * ({@code {id, data}}) every read here expects.
     *
     * @param document the stored document to parse
     * @return the parsed entry
     * @throws NoSuchDataFound if the document holds no {@code "data"} envelope, indicating a
     *                         corrupted or foreign document
     */
    private @NotNull DatabaseEntry readEntry(@NotNull Document document) {

        if (!document.containsKey("data")) throw new NoSuchDataFound(document.getString("id"));

        final JsonDocument jsonDocument = new JsonDocument(document.toJson());
        return new DatabaseEntry(document.getString("id"), new JsonDocument("data", jsonDocument.getMetaData("data")));

    }

    @Override
    protected void persistInsert(@NotNull DatabaseEntry databaseEntry) {

        // databaseEntry.getDocument() is already the full "data"-enveloped document (see its
        // own javadoc); appending it here as-is under another "data" key would double-wrap it,
        // so its already-unwrapped getMetaData() is used instead, matching persistUpdate() below.
        final String json = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData()).toJson();
        this.collection.insertOne(new JsonDocument().getGson().fromJson(json, Document.class));

    }

    @Override
    protected void persistUpdate(@NotNull DatabaseEntry databaseEntry) {

        final String json = new JsonDocument().append("id", databaseEntry.getId()).append("data", databaseEntry.getMetaData()).toJson();
        this.collection.updateOne(Filters.eq("id", databaseEntry.getId()), new Document("$set", new JsonDocument().getGson().fromJson(json, Document.class)));

    }

    @Override
    protected void persistDelete(@NotNull String id) {
        this.collection.deleteOne(Filters.eq("id", id));
    }

    @Override
    protected long countRemote() {
        return this.collection.countDocuments();
    }

    /**
     * {@inheritDoc}
     * <p>
     * A filtered {@code find} projected down to {@code _id} only, so the presence check never
     * transfers the (potentially large) {@code data} payload just to discard it.
     */
    @Override
    protected boolean existsRemote(@NotNull String id) {
        return this.collection.find(Filters.eq("id", id)).projection(Projections.include("_id")).first() != null;
    }

    @Override
    protected void clearRemote() {
        this.collection.deleteMany(new Document());
    }

}
