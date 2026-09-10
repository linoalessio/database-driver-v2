package de.lino.database.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-section cache configuration, passed to
 * {@link DatabaseProvider#createSection(String, SectionConfig)}. Instances are created through
 * the static factories ({@link #full()}, {@link #lazy()}, {@link #bounded(long)},
 * {@link #bounded(long, Duration)}, {@link #none()}) rather than the canonical constructor, so
 * a caller never has to pass placeholder values for settings their mode does not use.
 * <p>
 * {@code maxEntries} and {@code ttl} are meaningful for {@link CacheMode#BOUNDED} only; the
 * canonical constructor normalizes them away ({@code -1} / {@code null}) for every other mode,
 * so two configurations that behave identically also {@link #equals(Object) compare} equal -
 * providers rely on that equality to decide whether a repeated
 * {@code createSection(name, config)} call may reuse the existing section instance or must
 * replace it.
 *
 * @param cacheMode  how the section holds entries in memory
 * @param maxEntries for {@link CacheMode#BOUNDED}: the most entries ever held in memory at
 *                   once (must be positive); normalized to {@code -1} for every other mode
 * @param ttl        for {@link CacheMode#BOUNDED}: how long a cached entry stays valid before
 *                   it is re-read from the backing store, or {@code null} for no expiry -
 *                   this is the staleness bound for entries changed by <em>other</em>
 *                   processes, since the owning process' own writes always update the cache;
 *                   normalized to {@code null} for every other mode
 */
public record SectionConfig(@NotNull CacheMode cacheMode, long maxEntries, @Nullable Duration ttl) {

    /**
     * Validates and normalizes the configuration: a {@link CacheMode#BOUNDED} configuration
     * must actually bound something (positive {@code maxEntries}, no non-positive {@code ttl}),
     * and every other mode has its unused settings forced to their neutral values so equality
     * only ever reflects behavior (see the class documentation for why providers depend on
     * that).
     */
    public SectionConfig {

        Objects.requireNonNull(cacheMode, "@SectionConfig: cacheMode must not be null");

        if (cacheMode == CacheMode.BOUNDED) {
            if (maxEntries <= 0) throw new IllegalArgumentException("@SectionConfig: BOUNDED requires a positive maxEntries, got " + maxEntries);
            if (ttl != null && (ttl.isZero() || ttl.isNegative())) throw new IllegalArgumentException("@SectionConfig: ttl must be positive, got " + ttl);
        } else {
            maxEntries = -1;
            ttl = null;
        }

    }

    /**
     * The historical behavior and the implicit configuration of
     * {@link DatabaseProvider#createSection(String)}: every entry in memory, loaded when the
     * section is created.
     *
     * @return a {@link CacheMode#FULL} configuration
     */
    public static SectionConfig full() {
        return new SectionConfig(CacheMode.FULL, -1, null);
    }

    /**
     * Every entry in memory, but loaded on first data access instead of at section creation.
     *
     * @return a {@link CacheMode#LAZY} configuration
     */
    public static SectionConfig lazy() {
        return new SectionConfig(CacheMode.LAZY, -1, null);
    }

    /**
     * At most {@code maxEntries} entries in memory, managed as a read-through LRU cache with no
     * expiry - a cached entry only leaves memory by eviction or an explicit write.
     *
     * @param maxEntries the most entries ever held in memory at once; must be positive
     * @return a {@link CacheMode#BOUNDED} configuration without expiry
     */
    public static SectionConfig bounded(long maxEntries) {
        return new SectionConfig(CacheMode.BOUNDED, maxEntries, null);
    }

    /**
     * At most {@code maxEntries} entries in memory, each expiring {@code ttl} after it was
     * cached. The expiry is what bounds how stale a cached entry can get when <em>another</em>
     * process changes the backing store - the owning process' own writes always refresh the
     * cache immediately.
     *
     * @param maxEntries the most entries ever held in memory at once; must be positive
     * @param ttl        how long a cached entry stays valid; must be positive
     * @return a {@link CacheMode#BOUNDED} configuration with expiry
     */
    public static SectionConfig bounded(long maxEntries, @NotNull Duration ttl) {
        return new SectionConfig(CacheMode.BOUNDED, maxEntries, Objects.requireNonNull(ttl, "@SectionConfig.bounded: ttl must not be null"));
    }

    /**
     * Nothing in memory; every operation pushed down to the backing store.
     *
     * @return a {@link CacheMode#NONE} configuration
     */
    public static SectionConfig none() {
        return new SectionConfig(CacheMode.NONE, -1, null);
    }

}
