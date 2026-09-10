package de.lino.database.database.nosql.redis;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.AbstractLazyDatabaseProvider;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.notification.RedisCounterService;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link DatabaseProvider} backed by a Redis database, each {@link DatabaseSection} a
 * {@code "<name>:*"} key prefix via {@link RedisDatabaseSection}, all sharing this database's
 * single {@link JedisPool}. Section lifecycle and caching live in
 * {@link AbstractLazyDatabaseProvider}; this class only supplies the keyspace-level storage
 * operations - discovering prefixes, constructing a {@link RedisDatabaseSection}, wiping a
 * prefix. {@link JedisPool} is itself thread-safe and designed for concurrent multi-threaded
 * use, so every method here is safe to call concurrently without additional locking.
 */
public class RedisDatabaseProvider extends AbstractLazyDatabaseProvider {

    /**
     * How many keys each {@code SCAN} round trip asks the server for - the historical batch
     * size, bounding a scan's per-round-trip work without starving it.
     */
    private static final int SCAN_BATCH_SIZE = 100;

    /**
     * The connection pool shared by this database and every {@link RedisDatabaseSection} it creates.
     */
    private final JedisPool jedisPool;

    /**
     * Lazily constructed by {@link #counterService()}; {@code volatile} plus double-checked
     * locking there so concurrent first-callers can't each construct and race to publish their
     * own instance - both cheap to guard against and worth guarding, since every caller must end
     * up sharing the exact same {@link JedisRedisCounterService}, not one each.
     */
    private volatile RedisCounterService counterService;

    /**
     * Connects to a Redis database with {@code credentials} and discovers every existing key
     * prefix as a section name. Only names, from a single keyspace {@code SCAN} - no section
     * objects, no values - so construction cost is O(keys) once. Historically this constructor
     * created one section <em>per key</em> (not per prefix), each of which then ran its own
     * full-keyspace scan - O(keys²) work that degraded badly with key count, and section names
     * that were really key names; both are gone with prefix discovery.
     *
     * @param credentials the login credentials and connection details to connect with
     */
    public RedisDatabaseProvider(@NotNull Credentials credentials) {

        final JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(50);
        jedisPoolConfig.setMaxIdle(10);
        jedisPoolConfig.setMinIdle(2);
        jedisPoolConfig.setTestOnBorrow(true);

        if (credentials.getUserName().isEmpty() && credentials.getPassword().isEmpty()) {
            this.jedisPool = new JedisPool(jedisPoolConfig, credentials.getAddress(), credentials.getPort());
            try (final Jedis jedis = this.jedisPool.getResource()) {
                jedis.select(Integer.parseInt(credentials.getDatabase()));
            }
        } else {
            this.jedisPool = new JedisPool(jedisPoolConfig, "redis://:" + credentials.getPassword() + "@" + credentials.getAddress() + ":" + credentials.getPort() + "/" + credentials.getDatabase());
        }

        this.reload();

    }

    @Override
    public void shutdown() {
        this.jedisPool.close();
        this.forgetSections();
    }

    /**
     * {@inheritDoc}
     * <p>
     * A single cursor-based {@code SCAN} over the whole keyspace, reducing each key
     * {@code "<prefix>:<id>"} to its prefix (a key without a {@code ':'} passes through
     * whole, mirroring how {@link RedisDatabaseSection} would name it). Duplicates collapse in
     * the caller's name set, so N keys cost one O(N) pass - not the historical
     * one-scan-per-key O(N²).
     */
    @Override
    protected void discoverNames(@NotNull Consumer<String> consumer) {

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match("*").count(SCAN_BATCH_SIZE);

        try (final Jedis jedis = this.jedisPool.getResource()) {

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);

                for (final String key : result.getResult()) {
                    final int separator = key.indexOf(':');
                    consumer.accept(separator < 0 ? key : key.substring(0, separator));
                }

                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

    }

    @Override
    protected AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config) {
        return new RedisDatabaseSection(this.jedisPool, name, config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * A cursor-based {@code SCAN} over {@code "<name>:*"} with one {@code DEL} per scanned
     * batch rather than one per key, cutting round trips from O(matched keys) to
     * O(matched keys / {@value #SCAN_BATCH_SIZE}). The pattern deliberately includes the
     * {@code ':'} separator so deleting section {@code "users"} can never take keys of an
     * unrelated section that merely shares the character prefix (like {@code "users2"}) with
     * it.
     */
    @Override
    protected void dropSectionRemote(@NotNull String name) {

        try (final Jedis jedis = this.jedisPool.getResource()) {

            String cursor = "0";
            final ScanParams scanParams = new ScanParams().match(name + ":*").count(SCAN_BATCH_SIZE);

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);
                final List<String> keys = result.getResult();

                if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));

                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

    }

    /**
     * Returns this provider's {@link RedisCounterService}, constructing it on first call and
     * reusing that same instance afterward. The returned service shares {@link #jedisPool} with
     * every {@link RedisDatabaseSection} this provider manages, rather than opening a second,
     * independent {@link JedisPool} against the same Redis instance.
     *
     * @return this provider's lazily-constructed, shared {@link RedisCounterService}
     */
    public @NotNull RedisCounterService counterService() {

        RedisCounterService service = this.counterService;
        if (service != null) return service;

        synchronized (this) {
            service = this.counterService;
            if (service == null) {
                service = new JedisRedisCounterService(this.jedisPool);
                this.counterService = service;
            }
        }

        return service;

    }

}
