package de.lino.database.database.nosql.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link DatabaseProvider} backed by a MongoDB database, each {@link DatabaseSection} a
 * collection via {@link MongoDBDatabaseSection}. Section lifecycle and caching live in
 * {@link AbstractLazyDatabaseProvider}; this class only supplies the collection-level storage
 * operations - listing collections, constructing a {@link MongoDBDatabaseSection}, dropping a
 * collection. {@link MongoClient} and {@link MongoDatabase} are themselves thread-safe and
 * designed for concurrent multi-threaded use, so every method here is safe to call
 * concurrently without additional locking.
 */
public class MongoDBDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * Collection names that are never exposed as a {@link DatabaseSection}, since they are
     * MongoDB-internal rather than application data.
     */
    private static final List<String> FORBIDDEN = List.of("system.version", "system.users");

    /**
     * The client connection this database and every section it creates share.
     */
    private final MongoClient mongoClient;

    /**
     * The database this database is connected to.
     */
    private final MongoDatabase mongoDatabase;

    /**
     * Connects to a MongoDB database with {@code credentials} and discovers every existing,
     * non-{@link #FORBIDDEN} collection's name. Only names - no section objects, no documents -
     * so construction cost is one collection listing, independent of how much the database
     * holds.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public MongoDBDatabaseProvider(@NotNull Credentials credentials) {

        this.mongoClient = MongoClients.create(MessageFormat.format(
                "mongodb://{0}:{1}@{2}:{3}/{4}",
                credentials.getUserName(),
                URLEncoder.encode(credentials.getPassword(), StandardCharsets.UTF_8),
                credentials.getAddress(),
                Integer.toString(credentials.getPort()),
                credentials.getDatabase()
        ));

        this.mongoDatabase = this.mongoClient.getDatabase(credentials.getDatabase());

        this.reload();

    }

    @Override
    public void shutdown() {
        this.mongoClient.close();
        this.forgetSections();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Lists the database's collection names, skipping the {@link #FORBIDDEN} MongoDB-internal
     * ones.
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        for (final String name : this.mongoDatabase.listCollectionNames()) {
            if (FORBIDDEN.contains(name)) continue;
            consumer.accept(name);
        }

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new MongoDBDatabaseSection(this.mongoDatabase, name, config);
    }

    @Override
    protected void dropSectionRemote(@NotNull String name) {
        this.mongoDatabase.getCollection(name).drop();
    }

}
