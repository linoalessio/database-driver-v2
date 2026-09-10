package de.lino.database.database;

/**
 * How a {@link DatabaseSection} holds its entries in memory. Historically every section kept
 * every row of its backing store in heap for the whole process lifetime, which made both
 * startup time and the heap floor grow with the database's total size; the mode chosen per
 * section (via {@link SectionConfig}) is what breaks that coupling, letting small hot tables
 * stay fully materialized while large or rarely-touched ones stop costing memory at all.
 *
 * @see SectionConfig
 * @see DatabaseProvider#createSection(String, SectionConfig)
 */
public enum CacheMode {

    /**
     * Every entry is held in memory, loaded when the section is created and kept in sync by
     * every write - reads never touch the backing store. This is the historical behavior and
     * the default of {@link DatabaseProvider#createSection(String)}, so existing consumers keep
     * exactly the semantics (and warm-at-startup timing) they were built against. The right
     * choice for small, hot tables where read latency matters more than heap.
     */
    FULL,

    /**
     * Exactly {@link #FULL} once warm, but the one-time load happens on the first data access
     * instead of at section creation - creating the section costs nothing. The right choice for
     * hot tables that must not add to startup time; the first reader pays the load instead.
     */
    LAZY,

    /**
     * At most a configured number of entries are held in memory ({@link SectionConfig#maxEntries()},
     * optionally expiring after {@link SectionConfig#ttl()}), managed as a read-through
     * least-recently-used cache: a hit is served from memory, a miss is point-read from the
     * backing store and cached, and the oldest entries are evicted once the bound is exceeded.
     * The right choice for large tables with a hot working set - heap stays bounded no matter
     * how large the table grows.
     */
    BOUNDED,

    /**
     * Nothing is held in memory; every operation is pushed down to the backing store. The right
     * choice for unbounded, append-mostly tables (logs, versions, histories) that would
     * otherwise grow the heap without ever being read back hot.
     */
    NONE

}
