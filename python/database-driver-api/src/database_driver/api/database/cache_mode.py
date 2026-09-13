"""Mirror of ``de.lino.database.database.CacheMode``."""

from __future__ import annotations

from enum import Enum, unique


@unique
class CacheMode(Enum):
    """How a ``DatabaseSection`` holds its entries in memory.

    Historically every section kept every row of its backing store in memory for the whole
    process lifetime, which made both startup time and the memory floor grow with the
    database's total size; the mode chosen per section (via ``SectionConfig``) is what
    breaks that coupling, letting small hot tables stay fully materialized while large or
    rarely-touched ones stop costing memory at all.

    See Also:
        SectionConfig, DatabaseProvider.create_section
    """

    FULL = "FULL"
    """Every entry is held in memory, loaded when the section is created and kept in sync
    by every write - reads never touch the backing store. This is the historical behavior
    and the default of ``DatabaseProvider.create_section(name)``, so existing consumers
    keep exactly the semantics (and warm-at-startup timing) they were built against. The
    right choice for small, hot tables where read latency matters more than memory."""

    LAZY = "LAZY"
    """Exactly ``FULL`` once warm, but the one-time load happens on the first data access
    instead of at section creation - creating the section costs nothing. The right choice
    for hot tables that must not add to startup time; the first reader pays the load."""

    BOUNDED = "BOUNDED"
    """At most a configured number of entries are held in memory
    (``SectionConfig.max_entries``, optionally expiring after ``SectionConfig.ttl``),
    managed as a read-through least-recently-used cache: a hit is served from memory, a
    miss is point-read from the backing store and cached, and the oldest entries are
    evicted once the bound is exceeded. The right choice for large tables with a hot
    working set - memory stays bounded no matter how large the table grows."""

    NONE = "NONE"
    """Nothing is held in memory; every operation is pushed down to the backing store. The
    right choice for unbounded, append-mostly tables (logs, versions, histories) that
    would otherwise grow memory without ever being read back hot."""
