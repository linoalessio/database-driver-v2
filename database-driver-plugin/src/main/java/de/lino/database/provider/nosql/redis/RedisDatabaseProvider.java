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
import com.google.common.collect.Maps;
import de.lino.database.configuration.Credentials;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Map;

public class RedisDatabaseProvider implements DatabaseProvider {

    private final JedisPool jedisPool;
    private final Map<String, DatabaseSection> databaseSections;

    public RedisDatabaseProvider(@NotNull Credentials credentials) {

        this.databaseSections = Maps.newConcurrentMap();

        final JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(50);
        jedisPoolConfig.setMaxIdle(10);
        jedisPoolConfig.setMinIdle(2);
        jedisPoolConfig.setTestOnBorrow(true);

        if (credentials.getUserName().isEmpty() && credentials.getPassword().isEmpty()) {
            this.jedisPool = new JedisPool(jedisPoolConfig, credentials.getAddress(), credentials.getPort());
            this.jedisPool.getResource().select(Integer.parseInt(credentials.getDatabase()));
        } else {
            this.jedisPool = new JedisPool(jedisPoolConfig, "redis://:" + credentials.getPassword() + "@" + credentials.getAddress() + ":" + credentials.getPort() + "/" + credentials.getDatabase());
        }

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match("*").count(100);

        do {

            final ScanResult<String> result = this.jedisPool.getResource().scan(cursor, scanParams);
            for (String key : result.getResult())
                this.databaseSections.put(key, new RedisDatabaseSection(this.jedisPool.getResource(), key));

            cursor = result.getCursor();

        } while (!cursor.equals("0"));

    }

    @Override
    public void shutdown() {
        this.jedisPool.close();
        this.databaseSections.clear();
    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {

        if (this.databaseSections.containsKey(name)) return this.databaseSections.get(name);

        final DatabaseSection databaseSection = new RedisDatabaseSection(this.jedisPool.getResource(), name);
        this.databaseSections.put(name, databaseSection);

        return databaseSection;
    }

    @Override
    public void deleteSection(@NotNull String name) {

        try (final Jedis jedis = this.jedisPool.getResource()) {

            String cursor = "0";
            final ScanParams scanParams = new ScanParams().match(name + "*").count(100);

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) jedis.del(key);
                cursor = result.getCursor();

            } while (!cursor.equals("0"));

            this.databaseSections.remove(name);
        }
    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return Lists.newCopyOnWriteArrayList(this.databaseSections.values());
    }

    @Override
    public DatabaseSection getSection(@NotNull String name) {
        return this.databaseSections.get(name);
    }

    @Override
    public void clear() {
        for (DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.databaseSections.clear();
    }

}
