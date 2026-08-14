package de.lino.database.database.nosql.mongodb;

import com.google.common.collect.Maps;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseProvider} backed by a MongoDB database, each {@link DatabaseSection} a
 * collection via {@link MongoDBDatabaseSection}. {@link MongoClient} and {@link MongoDatabase}
 * are themselves thread-safe and designed for concurrent multi-threaded use, so every method
 * here is safe to call concurrently without additional locking.
 */
public class MongoDBDatabaseProvider implements DatabaseProvider {

    /**
     * Collection names that are never exposed as a {@link DatabaseSection}, since they are
     * MongoDB-internal rather than application data.
     */
    private static final List<String> FORBIDDEN = List.of("system.version", "system.users");

    /**
     * Every registered section, keyed by collection name.
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * The client connection this database and every section it creates share.
     */
    private final MongoClient mongoClient;

    /**
     * The database this database is connected to.
     */
    private final MongoDatabase mongoDatabase;

    /**
     * Connects to a MongoDB database with {@code credentials} and loads every existing,
     * non-{@link #FORBIDDEN} collection as a {@link MongoDBDatabaseSection}.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public MongoDBDatabaseProvider(@NotNull Credentials credentials) {

        this.databaseSections = Maps.newConcurrentMap();

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
        this.databaseSections.clear();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discards {@link #databaseSections} entirely and rebuilds it with a fresh
     * {@link MongoDBDatabaseSection} per non-{@link #FORBIDDEN} collection currently in
     * {@link #mongoDatabase}, the same scan the constructor itself runs.
     */
    @Override
    public void reload() {

        this.databaseSections.clear();

        for (String name : this.mongoDatabase.listCollectionNames()) {
            if (FORBIDDEN.contains(name)) continue;
            this.databaseSections.put(name, new MongoDBDatabaseSection(this.mongoDatabase, name));
        }

    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new MongoDBDatabaseSection(this.mongoDatabase, key));
    }

    @Override
    public void deleteSection(@NotNull String name) {
        this.mongoDatabase.getCollection(name).drop();
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
