package de.lino.database;


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
import com.google.common.collect.Maps;
import de.lino.database.configuration.Credentials;
import de.lino.database.file.DefaultFileProvider;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.DatabaseType;
import de.lino.database.provider.nosql.json.JsonDatabaseProvider;
import de.lino.database.provider.nosql.mongodb.MongoDBDatabaseProvider;
import de.lino.database.provider.sql.h2db.H2DatabaseProvider;
import de.lino.database.provider.sql.mariadb.MariaDBDatabaseProvider;
import de.lino.database.provider.sql.mysql.MySQLDatabaseProvider;
import de.lino.database.provider.sql.postgresql.PostgreSQLDatabaseProvider;
import de.lino.database.provider.sql.sqlite.SQLiteDatabaseProvider;
import de.lino.database.utils.Pair;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class DatabaseRepositoryRegistry extends DatabaseRepository {

    private final Map<Integer, Pair<DatabaseType, DatabaseProvider>> databaseProviders;

    public DatabaseRepositoryRegistry() {

        setInstance(this);
        this.databaseProviders = Maps.newConcurrentMap();
        new DefaultFileProvider();

    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool() {

        final List<DatabaseProvider> providers = Lists.newCopyOnWriteArrayList();
        this.databaseProviders.values().forEach(pair -> providers.add(pair.second()));

        return providers;
    }

    @Override
    public @UnmodifiableView List<DatabaseProvider> getDatabaseProviderPool(@NotNull DatabaseType databaseType) {

        final List<DatabaseProvider> providers = Lists.newCopyOnWriteArrayList();
        this.databaseProviders.values().forEach(pair -> {
            if (pair.first().equals(databaseType)) providers.add(pair.second());
        });

        return providers;
    }

    @Override
    public void shutdown() {

        this.databaseProviders.forEach((key, value) -> {
            value.second().shutdown();
            System.out.println("Database Provider with id #" + key + " (" + this.getDatabaseProviders().get(key).first() + ") successfully unregistered");
        });

        this.databaseProviders.clear();

    }

    @Override
    public Pair<DatabaseProvider, DatabaseProvider> convert(@NotNull int sourceId, @NotNull int targetId) {

        final DatabaseProvider source = this.databaseProviders.get(sourceId).second();
        final DatabaseProvider destination = this.databaseProviders.get(targetId).second();

        source.getSections().forEach(section -> {

            System.out.println(section.getName());
            if (destination.existsSection(section.getName()))
                destination.deleteSection(section.getName());

            final DatabaseSection databaseSection = destination.createSection(section.getName());
            section.getEntries().forEach(databaseSection::insert);

        });

        System.out.println("Database Provider with id #" + sourceId + " (" + this.getDatabaseProviders().get(sourceId).first() + ") successfully converted to database provider with id #" + targetId + " (" + this.databaseProviders.get(targetId).first() + ")");
        return new Pair<>(source, destination);
    }

    @Override
    public Optional<DatabaseProvider> findDatabaseProviderById(@NotNull int id) {
        return Optional.ofNullable(this.databaseProviders.get(id).second());
    }

    @Override
    public DatabaseProvider registerDatabaseProvider(@NotNull int id, @NotNull DatabaseType databaseType, @NotNull Credentials credentials) {

        if (this.databaseProviders.containsKey(id)) throw new IllegalStateException("Database Provider with id #" + id + " already exists");

        final AtomicReference<DatabaseProvider> databaseProviderAtomicReference = new AtomicReference<>();

        switch (databaseType) {

            case MY_SQL -> databaseProviderAtomicReference.set(new MySQLDatabaseProvider(credentials));
            case JSON -> databaseProviderAtomicReference.set(new JsonDatabaseProvider(credentials));
            case H2_DB -> databaseProviderAtomicReference.set(new H2DatabaseProvider(credentials));
            case MONGO_DB -> databaseProviderAtomicReference.set(new MongoDBDatabaseProvider(credentials));
            case POSTGRE_SQL -> databaseProviderAtomicReference.set(new PostgreSQLDatabaseProvider(credentials));
            case SQLITE -> databaseProviderAtomicReference.set(new SQLiteDatabaseProvider(credentials));
            case MARIA_DB -> databaseProviderAtomicReference.set(new MariaDBDatabaseProvider(credentials));
            case RETHINK_DB -> databaseProviderAtomicReference.set(null);

        }

        this.databaseProviders.put(id, new Pair<>(databaseType, databaseProviderAtomicReference.get()));
        System.out.println("Database Provider with id #" + id + " (" + this.getDatabaseProviders().get(id).first() + ") successfully registered");
        return databaseProviderAtomicReference.get();
    }

    @Override
    public DatabaseProvider unregisterDatabaseProvider(@NotNull int id) {

        if (!this.databaseProviders.containsKey(id)) throw new IllegalStateException("Database Provider with id #" + id + " does not exist");

        final DatabaseProvider unregistered = this.databaseProviders.get(id).second();
        unregistered.shutdown();
        this.databaseProviders.remove(id);
        System.out.println("Database Provider with id #" + id + " (" + this.getDatabaseProviders().get(id).first() + ") successfully unregistered");

        return unregistered;

    }

}
