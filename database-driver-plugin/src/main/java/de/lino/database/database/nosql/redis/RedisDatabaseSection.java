package de.lino.database.database.nosql.redis;

import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.CacheMode;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one Redis key prefix ({@code "<name>:<id>"} per entry).
 * All caching lives in {@link AbstractCachedDatabaseSection}; this class only supplies the key
 * prefix's storage primitives - point primitives as single-key commands, whole-section
 * primitives as cursor-based {@code SCAN} passes so no primitive ever blocks the server the way
 * a {@code KEYS} call would.
 */
public class RedisDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The Redis Pub/Sub channel every {@link #persistInsert}/{@link #persistUpdate} call
     * unconditionally {@code PUBLISH}es a change notification to, in the exact {@code {"table",
     * "operation", "id"}} JSON shape {@code PostgresDatabaseNotification}'s own trigger function
     * emits, so a consumer needs no special-casing between the two backends. This is one fixed
     * channel shared by every {@link RedisDatabaseSection} on a given Redis instance, not a
     * per-section or per-provider setting - unlike Postgres, which binds an arbitrary,
     * caller-chosen channel to each table via its own trigger, Redis has no server-side trigger
     * concept to bind a channel to a key prefix with, so there is nothing to make this
     * configurable per instance. A {@code RedisDatabaseNotification} must be constructed with
     * this exact channel name to observe these publishes.
     */
    public static final String CHANGE_NOTIFICATION_CHANNEL = "database-driver-changes";

    /**
     * How many keys each {@code SCAN} round trip asks the server for, in every cursor-based
     * primitive here - the historical batch size, bounding a scan's per-round-trip work
     * without starving it.
     */
    private static final int SCAN_BATCH_SIZE = 100;

    /**
     * The connection pool shared with this section's owning {@link RedisDatabaseProvider} and
     * every one of its sibling sections.
     */
    private final JedisPool jedisPool;

    /**
     * Loads every existing {@code "<name>:*"} key into memory immediately - the historical
     * constructor, kept with its exact loaded-once-constructed semantics for anyone
     * instantiating sections directly rather than through a provider.
     *
     * @param jedisPool the connection pool to run every command through
     * @param name      this section's key prefix
     */
    public RedisDatabaseSection(@NotNull final JedisPool jedisPool, @NotNull final String name) {
        this(jedisPool, name, SectionConfig.full());
        this.warmUp();
    }

    /**
     * Prepares the section without touching Redis at all - a key prefix needs no server-side
     * container, and whether and when keys are read is the engine's decision per
     * {@code config}, with the owning provider triggering the {@link CacheMode#FULL} warm-up
     * right after construction.
     *
     * @param jedisPool the connection pool to run every command through
     * @param name      this section's key prefix
     * @param config    how this section holds entries in memory
     */
    public RedisDatabaseSection(@NotNull final JedisPool jedisPool, @NotNull final String name, @NotNull final SectionConfig config) {

        super(name, config);
        this.jedisPool = jedisPool;

    }

    /**
     * {@inheritDoc}
     * <p>
     * A cursor-based {@code SCAN} over {@code "<name>:*"} with a per-key {@code GET}, in
     * {@value #SCAN_BATCH_SIZE}-key batches.
     */
    @Override
    protected void loadAll(@NotNull final Consumer<DatabaseEntry> consumer) {

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match(this.getName() + ":*").count(SCAN_BATCH_SIZE);

        try (final Jedis jedis = this.jedisPool.getResource()) {

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);

                for (final String key : result.getResult()) {

                    final byte[] data = jedis.get(key.getBytes());
                    if (data == null) throw new NoSuchDataFound(key);

                    consumer.accept(new DatabaseEntry(key.replace(this.getName() + ":", ""), new JsonDocument(data)));

                }

                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single {@code GET} on the entry's full key - Redis' native point read.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull final String id) {

        try (final Jedis jedis = this.jedisPool.getResource()) {

            final byte[] data = jedis.get(this.entryKey(id).getBytes());
            return data == null ? Optional.empty() : Optional.of(new DatabaseEntry(id, new JsonDocument(data)));

        }

    }

    @Override
    protected void persistInsert(@NotNull final DatabaseEntry databaseEntry) {

        // databaseEntry.getDocument() is already the full "data"-enveloped document (see its
        // own javadoc); appending it here as-is under another "data" key would double-wrap it,
        // so its already-unwrapped getMetaData() is used instead, matching persistUpdate() below.
        try (final Jedis jedis = this.jedisPool.getResource()) {
            jedis.set(this.entryKey(databaseEntry.getId()).getBytes(), new JsonDocument().append("data", databaseEntry.getMetaData()).toBytes());
            this.publishChangeNotification(jedis, "INSERT", databaseEntry.getId());
        }

    }

    @Override
    protected void persistUpdate(@NotNull final DatabaseEntry databaseEntry) {

        try (final Jedis jedis = this.jedisPool.getResource()) {
            jedis.set(this.entryKey(databaseEntry.getId()).getBytes(), new JsonDocument().append("data", databaseEntry.getMetaData()).toBytes());
            this.publishChangeNotification(jedis, "UPDATE", databaseEntry.getId());
        }

    }

    @Override
    protected void persistDelete(@NotNull final String id) {

        try (final Jedis jedis = this.jedisPool.getResource()) {
            jedis.del(this.entryKey(id).getBytes());
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A full cursor-based {@code SCAN} over {@code "<name>:*"}, counting matches - Redis keeps
     * no per-prefix key count, so this is O(keyspace) per call and a full in-memory mode
     * answers {@code count()} far cheaper for hot sections.
     */
    @Override
    protected long countRemote() {

        long count = 0;
        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match(this.getName() + ":*").count(SCAN_BATCH_SIZE);

        try (final Jedis jedis = this.jedisPool.getResource()) {

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);
                count += result.getResult().size();
                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

        return count;

    }

    /**
     * {@inheritDoc}
     * <p>
     * A single {@code EXISTS} on the entry's full key - Redis' native point check.
     */
    @Override
    protected boolean existsRemote(@NotNull final String id) {

        try (final Jedis jedis = this.jedisPool.getResource()) {
            return jedis.exists(this.entryKey(id));
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A cursor-based {@code SCAN} over {@code "<name>:*"} with one {@code DEL} per scanned
     * batch rather than one per key, cutting round trips from O(matched keys) to
     * O(matched keys / {@value #SCAN_BATCH_SIZE}).
     */
    @Override
    protected void clearRemote() {

        String cursor = "0";
        final ScanParams scanParams = new ScanParams().match(this.getName() + ":*").count(SCAN_BATCH_SIZE);

        try (final Jedis jedis = this.jedisPool.getResource()) {

            do {

                final ScanResult<String> result = jedis.scan(cursor, scanParams);
                final List<String> keys = result.getResult();

                if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));

                cursor = result.getCursor();

            } while (!cursor.equals("0"));

        }

    }

    /**
     * Builds the full Redis key an entry with {@code id} is stored under - the single naming
     * rule ({@code "<name>:<id>"}) every primitive above shares.
     *
     * @param id the entry's id
     * @return the entry's full Redis key
     */
    private @NotNull String entryKey(@NotNull final String id) {
        return this.getName() + ":" + id;
    }

    /**
     * {@code PUBLISH}es a {@code {"table", "operation", "id"}} change notification on
     * {@link #CHANGE_NOTIFICATION_CHANNEL}, reusing the same {@code jedis} connection the calling
     * write already borrowed rather than checking out a second one. Unconditional - {@code
     * PUBLISH} to a channel with zero subscribers is a cheap, single round trip in Redis, so this
     * runs on every write with no "is anyone listening" gate.
     *
     * @param jedis     the connection to publish through, borrowed by the caller
     * @param operation {@code "INSERT"} or {@code "UPDATE"}, matching the values {@code
     *                  PostgresDatabaseNotification}'s trigger function emits for the same cases
     * @param id        the written entry's id
     */
    private void publishChangeNotification(@NotNull final Jedis jedis, @NotNull final String operation, @NotNull final String id) {
        final String payload = new JsonDocument()
                .append("table", this.getName())
                .append("operation", operation)
                .append("id", id)
                .toJson();
        jedis.publish(CHANGE_NOTIFICATION_CHANNEL, payload);
    }

}
