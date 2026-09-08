package de.lino.database.database.nosql.redis;

import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The one {@link RedisCounterService} implementation, backed by {@link Jedis#eval}. Constructed
 * from an already-built {@link RedisDatabaseProvider} via {@link RedisDatabaseProvider#counterService()}
 * rather than directly, so it always shares that provider's own {@link JedisPool} instead of a
 * caller opening a second, independent pool against the same Redis instance.
 */
public final class JedisRedisCounterService implements RedisCounterService {

    /**
     * {@code KEYS[1]} is the counter key, {@code ARGV[1]} the window's TTL in seconds.
     * Branching on {@code INCR}'s result to decide whether to also call {@code EXPIRE} is exactly
     * what makes this atomic - {@code MULTI}/{@code EXEC} alone cannot express that branch, since
     * every command in a transaction is queued blind, before any of their results are known.
     */
    private static final String INCREMENT_WITH_EXPIRY_SCRIPT =
            "local n = redis.call('INCR', KEYS[1]); " +
            "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; " +
            "return n";

    /**
     * The connection pool shared with this instance's owning {@link RedisDatabaseProvider}.
     */
    private final JedisPool jedisPool;

    /**
     * @param jedisPool the connection pool to run every command through
     */
    JedisRedisCounterService(@NotNull final JedisPool jedisPool) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "@JedisRedisCounterService.init: jedisPool cannot be null");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Runs {@link #INCREMENT_WITH_EXPIRY_SCRIPT} as a single {@link Jedis#eval} call - Redis
     * executes a Lua script as one atomic step, so no two concurrent callers (even across
     * different processes) can observe each other's half-applied state.
     */
    @Override
    public long incrementAndGetWithExpiry(@NotNull final String key, final long windowSeconds) {

        Objects.requireNonNull(key, "@JedisRedisCounterService.incrementAndGetWithExpiry: key cannot be null");

        final List<String> keys = Collections.singletonList(key);
        final List<String> args = Collections.singletonList(String.valueOf(windowSeconds));

        try (final Jedis jedis = this.jedisPool.getResource()) {
            return (Long) jedis.eval(INCREMENT_WITH_EXPIRY_SCRIPT, keys, args);
        }

    }

    @Override
    public long getCount(@NotNull final String key) {

        Objects.requireNonNull(key, "@JedisRedisCounterService.getCount: key cannot be null");

        try (final Jedis jedis = this.jedisPool.getResource()) {
            final String value = jedis.get(key);
            return value == null ? 0L : Long.parseLong(value);
        }

    }

    @Override
    public void reset(@NotNull final String key) {

        Objects.requireNonNull(key, "@JedisRedisCounterService.reset: key cannot be null");

        try (final Jedis jedis = this.jedisPool.getResource()) {
            jedis.del(key);
        }

    }

}
