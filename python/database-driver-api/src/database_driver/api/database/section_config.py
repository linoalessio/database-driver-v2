"""Mirror of ``de.lino.database.database.SectionConfig``."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta

from database_driver.api.database.cache_mode import CacheMode


@dataclass(frozen=True, slots=True)
class SectionConfig:
    """Per-section cache configuration, passed to ``DatabaseProvider.create_section``.

    Instances are created through the static factories (:meth:`full`, :meth:`lazy`,
    :meth:`bounded`, :meth:`none`) rather than the constructor, so a caller never has to
    pass placeholder values for settings their mode does not use.

    ``max_entries`` and ``ttl`` are meaningful for :attr:`CacheMode.BOUNDED` only; the
    constructor normalizes them away (``-1`` / ``None``) for every other mode, so two
    configurations that behave identically also compare equal - providers rely on that
    equality to decide whether a repeated ``create_section(name, config)`` call may reuse
    the existing section instance or must replace it.

    Attributes:
        cache_mode: How the section holds entries in memory.
        max_entries: For :attr:`CacheMode.BOUNDED`: the most entries ever held in memory
            at once (must be positive); normalized to ``-1`` for every other mode.
        ttl: For :attr:`CacheMode.BOUNDED`: how long a cached entry stays valid before it
            is re-read from the backing store, or ``None`` for no expiry - this is the
            staleness bound for entries changed by *other* processes, since the owning
            process' own writes always update the cache; normalized to ``None`` for every
            other mode.
    """

    cache_mode: CacheMode
    max_entries: int = field(default=-1)
    ttl: timedelta | None = field(default=None)

    def __post_init__(self) -> None:
        """Validates and normalizes the configuration.

        A :attr:`CacheMode.BOUNDED` configuration must actually bound something (positive
        ``max_entries``, no non-positive ``ttl``), and every other mode has its unused
        settings forced to their neutral values so equality only ever reflects behavior
        (see the class documentation for why providers depend on that).
        """
        if not isinstance(self.cache_mode, CacheMode):
            raise TypeError("@SectionConfig: cache_mode must be a CacheMode")

        if self.cache_mode is CacheMode.BOUNDED:
            if self.max_entries <= 0:
                raise ValueError(f"@SectionConfig: BOUNDED requires a positive max_entries, got {self.max_entries}")
            if self.ttl is not None and self.ttl <= timedelta(0):
                raise ValueError(f"@SectionConfig: ttl must be positive, got {self.ttl}")
        else:
            # frozen dataclass: normalization must go through object.__setattr__, exactly
            # like the compact-constructor reassignment in the Java record.
            object.__setattr__(self, "max_entries", -1)
            object.__setattr__(self, "ttl", None)

    @staticmethod
    def full() -> SectionConfig:
        """The historical behavior and the implicit configuration of
        ``DatabaseProvider.create_section(name)``: every entry in memory, loaded when the
        section is created."""
        return SectionConfig(CacheMode.FULL)

    @staticmethod
    def lazy() -> SectionConfig:
        """Every entry in memory, but loaded on first data access instead of at section
        creation."""
        return SectionConfig(CacheMode.LAZY)

    @staticmethod
    def bounded(max_entries: int, ttl: timedelta | None = None) -> SectionConfig:
        """At most ``max_entries`` entries in memory, managed as a read-through LRU cache.

        Without ``ttl`` a cached entry only leaves memory by eviction or an explicit
        write; with ``ttl`` each entry additionally expires that long after it was cached.
        The expiry is what bounds how stale a cached entry can get when *another* process
        changes the backing store - the owning process' own writes always refresh the
        cache immediately.

        Args:
            max_entries: The most entries ever held in memory at once; must be positive.
            ttl: How long a cached entry stays valid; must be positive if given.
        """
        return SectionConfig(CacheMode.BOUNDED, max_entries, ttl)

    @staticmethod
    def none() -> SectionConfig:
        """Nothing in memory; every operation pushed down to the backing store."""
        return SectionConfig(CacheMode.NONE)
