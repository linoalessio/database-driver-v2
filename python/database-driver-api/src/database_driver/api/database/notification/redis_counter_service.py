"""Mirror of ``de.lino.database.database.notification.RedisCounterService``."""

from __future__ import annotations

from abc import ABC, abstractmethod


class RedisCounterService(ABC):
    """A Redis-specific atomic counter primitive, deliberately kept off the generic
    ``DatabaseProvider``/``DatabaseSection`` contract - a race-free "increment, and arm a
    TTL exactly once on the first hit of a fresh window" operation has no equivalent in
    the plain CRUD shape every other backend implements, and forcing it onto that shared
    interface would make every other backend either implement or explicitly no-op a
    concept that only makes sense for a key/value store with native atomic commands.

    Typical use is a shared, cross-process fixed-window rate limiter: concurrent callers
    on different processes/instances hitting :meth:`increment_and_get_with_expiry` for
    the same key never lose a count and never re-arm the window's TTL past its first hit,
    which a plain read-then-write against a Redis section could not guarantee.
    """

    @abstractmethod
    def increment_and_get_with_expiry(self, key: str, window_seconds: int) -> int:
        """Atomically increments ``key`` by one and returns the resulting count.

        If this increment is what brought ``key`` from absent/``0`` to ``1`` - i.e. the
        first hit of a fresh window - this call also arms ``key``'s TTL to
        ``window_seconds`` in the same atomic step, so a burst of concurrent callers on
        the same fresh key can never both observe ``0``, both increment to ``1``, and
        each re-arm the TTL (which would extend the window forever).

        Args:
            key: The counter key to increment.
            window_seconds: The TTL, in seconds, to arm on the first hit of a fresh
                window; ignored on every subsequent hit until the key expires or
                :meth:`reset` is called.

        Returns:
            The counter's value after this increment.
        """

    @abstractmethod
    def get_count(self, key: str) -> int:
        """Returns ``key``'s current count, or ``0`` if it does not exist or has
        expired."""

    @abstractmethod
    def reset(self, key: str) -> None:
        """Deletes ``key`` outright, discarding both its count and its TTL - intended for
        tests and manual resets, not for normal window rollover (which
        :meth:`increment_and_get_with_expiry` already handles via expiry)."""
