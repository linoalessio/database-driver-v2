"""Mirror of ``de.lino.database.database.nosql.redis.JedisRedisCounterService`` - the
class name swaps Jedis for redis-py, this edition's client."""

from __future__ import annotations

from typing import Any

from database_driver.api.database.notification.redis_counter_service import RedisCounterService

_INCREMENT_WITH_EXPIRY_SCRIPT = (
    "local n = redis.call('INCR', KEYS[1]); "
    "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; "
    "return n"
)
"""``KEYS[1]`` is the counter key, ``ARGV[1]`` the window's TTL in seconds. Branching on
``INCR``'s result to decide whether to also call ``EXPIRE`` is exactly what makes this
atomic - ``MULTI``/``EXEC`` alone cannot express that branch, since every command in a
transaction is queued blind, before any of their results are known."""


class RedisPyCounterService(RedisCounterService):
    """The one ``RedisCounterService`` implementation, backed by Redis ``EVAL``.
    Constructed from an already-built ``RedisDatabaseProvider`` via its
    ``counter_service()`` accessor rather than directly, so it always shares that
    provider's own client (and connection pool) instead of a caller opening a second,
    independent pool against the same Redis instance."""

    def __init__(self, client: Any) -> None:
        if client is None:
            raise TypeError("@RedisPyCounterService.init: client cannot be None")
        self._client = client

    def increment_and_get_with_expiry(self, key: str, window_seconds: int) -> int:
        """Runs the increment-with-expiry script as a single ``EVAL`` call - Redis
        executes a Lua script as one atomic step, so no two concurrent callers (even
        across different processes) can observe each other's half-applied state."""
        if key is None:
            raise TypeError("@RedisPyCounterService.increment_and_get_with_expiry: key cannot be None")
        return int(self._client.eval(_INCREMENT_WITH_EXPIRY_SCRIPT, 1, key, str(window_seconds)))

    def get_count(self, key: str) -> int:
        if key is None:
            raise TypeError("@RedisPyCounterService.get_count: key cannot be None")
        value = self._client.get(key)
        return 0 if value is None else int(value)

    def reset(self, key: str) -> None:
        if key is None:
            raise TypeError("@RedisPyCounterService.reset: key cannot be None")
        self._client.delete(key)
