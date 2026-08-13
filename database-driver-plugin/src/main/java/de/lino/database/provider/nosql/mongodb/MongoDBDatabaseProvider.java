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

import com.google.common.collect.Maps;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.lino.database.configuration.Credentials;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
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
     * The client connection this provider and every section it creates share.
     */
    private final MongoClient mongoClient;

    /**
     * The database this provider is connected to.
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

        for (String name : this.mongoDatabase.listCollectionNames()) {
            if (FORBIDDEN.contains(name)) continue;
            this.databaseSections.put(name, new MongoDBDatabaseSection(this.mongoDatabase, name));
        }

    }

    @Override
    public void shutdown() {
        this.mongoClient.close();
        this.databaseSections.clear();
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
