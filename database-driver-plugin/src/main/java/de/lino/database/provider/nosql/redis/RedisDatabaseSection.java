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

import com.google.common.collect.Lists;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.exception.EntryAlreadyInserted;
import de.lino.database.exception.NoSuchDataFound;
import de.lino.database.exception.NoSuchEntryFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Optional;

public class RedisDatabaseSection implements DatabaseSection {

    private final Jedis jedis;

    @Getter
    private final String name;

    @Getter
    private final List<DatabaseEntry> entries;

    public RedisDatabaseSection(final Jedis jedis, final String name) {

        this.name = name;
        this.jedis = jedis;
        this.entries = Lists.newCopyOnWriteArrayList();

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match(name + ":*").count(100);

        do {

            final ScanResult<String> result = jedis.scan(cursor, scanParams);

            for (String key : result.getResult()) {

                final byte[] data = jedis.get(key.getBytes());
                if (data == null) throw new NoSuchDataFound(key);

                final DatabaseEntry databaseEntry = new DatabaseEntry(key.replace(this.name + ":", ""), new JsonDocument(data));
                this.entries.add(databaseEntry);

            }

            cursor = result.getCursor();

        } while (!cursor.equals("0"));

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.exists(databaseEntry.getId())) throw new EntryAlreadyInserted(databaseEntry.getId());

        final String key = this.name + ":" + databaseEntry.getId();

        this.jedis.set(key.getBytes(), new JsonDocument().append("data", databaseEntry.getDocument()).toBytes());
        this.entries.add(databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());

        final String key = this.name + ":" + databaseEntry.getId();
        this.jedis.set(key.getBytes(), new JsonDocument().append("data", databaseEntry.getMetaData()).toBytes());

        this.entries.removeIf(entry -> entry.getId().equals(databaseEntry.getId()));
        this.entries.add(databaseEntry);

        DatabaseRepositoryRegistry.logBytes("The database entry contained %d Bytes", databaseEntry.getDocument());

    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) throw new NoSuchEntryFound(id);

        final String key = this.name + ":" + id;
        this.jedis.del(key.getBytes());
        this.entries.removeIf(databaseEntry -> databaseEntry.getId().equals(id));

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void clear() {

        for (DatabaseEntry entry : this.entries) this.delete(entry.getId());

    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.stream().anyMatch(databaseEntry -> databaseEntry.getId().equals(id));
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.stream().filter(databaseEntry -> databaseEntry.getId().equals(id)).findFirst().orElse(null));
    }

}
