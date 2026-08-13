package de.lino.database.provider.nosql.redis;

/*
 * MIT License
 *
 * Copyright (c) lino, 14.09.2025
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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link DatabaseSection} backing one Redis key prefix ({@code "<name>:<id>"} per entry).
 * Entries are cached in memory (loaded once in the constructor and kept in sync on every write)
 * so reads never touch Redis, only writes do.
 */
public class RedisDatabaseSection implements DatabaseSection {

    /**
     * The connection pool shared with this section's owning {@link RedisDatabaseProvider} and
     * every one of its sibling sections.
     */
    private final JedisPool jedisPool;

    /**
     * This section's key prefix.
     */
    @Getter
    private final String name;

    /**
     * Every entry currently under {@link #name}'s key prefix, keyed by id and kept in sync with
     * Redis by every write method; the source of truth for every read method.
     */
    private final Map<String, DatabaseEntry> entries;

    /**
     * Loads every existing {@code "<name>:*"} key into {@link #entries}.
     *
     * @param jedisPool the connection pool to run every command through
     * @param name      this section's key prefix
     */
    public RedisDatabaseSection(@NotNull final JedisPool jedisPool, @NotNull final String name) {

        this.name = name;
        this.jedisPool = jedisPool;
        this.entries = Maps.newConcurrentMap();

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match(name + ":*").count(100);

        try (final Jedis jedis = jedisPool.getResource()) {

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);

                for (String key : result.getResult()) {

                    final byte[] data = jedis.get(key.getBytes());
                    if (data == null) throw new NoSuchDataFound(key);

                    final DatabaseEntry databaseEntry = new DatabaseEntry(key.replace(this.name + ":", ""), new JsonDocument(data));
                    this.entries.put(databaseEntry.getId(), databaseEntry);

                }

                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.entries.putIfAbsent(databaseEntry.getId(), databaseEntry) != null) throw new EntryAlreadyInserted(databaseEntry.getId());

        final String key = this.name + ":" + databaseEntry.getId();

        try (final Jedis jedis = jedisPool.getResource()) {
            jedis.set(key.getBytes(), new JsonDocument().append("data", databaseEntry.getDocument()).toBytes());
        }

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        final String key = this.name + ":" + databaseEntry.getId();
        try (final Jedis jedis = jedisPool.getResource()) {
            jedis.set(key.getBytes(), new JsonDocument().append("data", databaseEntry.getMetaData()).toBytes());
        }

        this.entries.put(databaseEntry.getId(), databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        final String key = this.name + ":" + id;
        try (final Jedis jedis = jedisPool.getResource()) {
            jedis.del(key.getBytes());
        }
        this.entries.remove(id);

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {

        if (this.entries.isEmpty()) return;

        // One DEL for every key at once, rather than one round trip per entry via delete().
        final String[] keys = this.entries.keySet().stream().map(id -> this.name + ":" + id).toArray(String[]::new);

        try (final Jedis jedis = jedisPool.getResource()) {
            jedis.del(keys);
        }

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
