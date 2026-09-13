"""Mirror of ``de.lino.database.database.entity.Serialized``."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TypeVar

from database_driver.api.json.json_document import JsonDocument

T = TypeVar("T", bound="Serialized")


class Serialized(ABC):
    """Base class for domain entities that can be persisted through the database-driver
    plugin and printed to the console.

    :meth:`keys_of` exposes the entity's identifying values, e.g. its primary key and any
    other unique attributes, in a fixed order, so a driver implementation or calling code
    can look a specific instance up by one of them without needing extra metadata about
    the entity's structure. Subclasses must supply their own ``__str__`` and ``__eq__``
    implementations, since neither can be derived generically from :meth:`keys_of`.

    The Java edition additionally implements ``java.io.Serializable``; Python objects
    need no marker interface for that, and the driver's own byte-array round-trip
    (:meth:`to_byte_array` / :meth:`from_byte_array`) is JSON-based in both editions.
    """

    @abstractmethod
    def keys_of(self) -> list[str]:
        """Returns this entity's identifying values, e.g. its primary key and any other
        unique attributes such as an email address, in a fixed order. Used to look this
        entity up by one of those values, see :meth:`has_key`.

        By convention the first element is this entity's primary key; see
        :meth:`primary_key`.
        """

    def primary_key(self) -> str:
        """Returns this entity's primary key - by convention the first value returned by
        :meth:`keys_of`."""
        return self.keys_of()[0]

    def has_key(self, key: str) -> bool:
        """Checks whether the given value matches one of this entity's identifying
        values, ignoring case, e.g. whether it is this entity's primary key or one of its
        other unique attributes.

        Raises:
            TypeError: If ``key`` is ``None``.
        """
        if key is None:
            raise TypeError("@Serialized.has_key: key cannot be None")
        return any(key.casefold() == candidate.casefold() for candidate in self.keys_of())

    def keys_as_string(self) -> str:
        """Joins all values returned by :meth:`keys_of` into a single colon-separated
        string, primarily for compact logging and console output."""
        return ":".join(self.keys_of())

    def to_byte_array(self) -> bytes:
        """Serializes this entity to a UTF-8 encoded JSON byte array, wrapping it under a
        ``"serialized"`` entry so it can be read back by :meth:`from_byte_array`."""
        return JsonDocument().append("serialized", self).to_bytes()

    @staticmethod
    def from_byte_array(data: bytes, entity_type: type[T]) -> T:
        """Deserializes an entity previously produced by :meth:`to_byte_array` into an
        instance of the given type."""
        result = JsonDocument(data).get("serialized", entity_type)
        if not isinstance(result, entity_type):
            raise TypeError(f"@Serialized.from_byte_array: payload did not decode to {entity_type.__name__}")
        return result
