package de.lino.database.database.nosql.redis;

import org.jetbrains.annotations.NotNull;

/**
 * A Redis-specific atomic counter primitive, deliberately kept off the generic
 * {@link de.lino.database.database.DatabaseProvider}/{@link de.lino.database.database.DatabaseSection}
 * contract - a race-free "increment, and arm a TTL exactly once on the first hit of a fresh
 * window" operation has no equivalent in the plain CRUD shape every other backend (SQL variants,
 * Mongo, RethinkDB, JSON, CSV) implements, and forcing it onto that shared interface would make
 * every other backend either implement or explicitly no-op a concept that only makes sense for a
 * key/value store with native atomic commands.
 * <p>
 * Typical use is a shared, cross-process fixed-window rate limiter: concurrent callers on
 * different JVMs/instances hitting {@link #incrementAndGetWithExpiry(String, long)} for the same
 * key never lose a count and never re-arm the window's TTL past its first hit, which a plain
 * read-then-write against a {@link RedisDatabaseSection} could not guarantee.
 */
public interface RedisCounterService {

    /**
     * Atomically increments {@code key} by one and returns the resulting count. If this
     * increment is what brought {@code key} from absent/{@code 0} to {@code 1} - i.e. the first
     * hit of a fresh window - this call also arms {@code key}'s TTL to {@code windowSeconds} in
     * the same atomic step, so a burst of concurrent callers on the same fresh key can never both
     * observe {@code 0}, both increment to {@code 1}, and each re-arm the TTL (which would extend
     * the window forever).
     *
     * @param key           the counter key to increment
     * @param windowSeconds the TTL, in seconds, to arm on the first hit of a fresh window; ignored
     *                      on every subsequent hit until the key expires or {@link #reset(String)}
     *                      is called
     * @return the counter's value after this increment
     */
    long incrementAndGetWithExpiry(@NotNull String key, long windowSeconds);

    /**
     * @param key the counter key to read
     * @return {@code key}'s current count, or {@code 0} if it does not exist or has expired
     */
    long getCount(@NotNull String key);

    /**
     * Deletes {@code key} outright, discarding both its count and its TTL - intended for tests
     * and manual resets, not for normal window rollover (which {@link #incrementAndGetWithExpiry}
     * already handles via expiry).
     *
     * @param key the counter key to delete
     */
    void reset(@NotNull String key);

}
