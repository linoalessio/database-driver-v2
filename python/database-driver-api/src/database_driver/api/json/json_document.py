"""Mirror of ``de.lino.database.json.JsonDocument``."""

from __future__ import annotations

import io
import json as _json
import os
from collections.abc import Callable, Mapping
from decimal import Decimal
from pathlib import Path
from typing import Any, TypeVar

from database_driver.api.json.adapter.json_document_type_adapter import from_jsonable, to_jsonable
from database_driver.api.json.parser import document_json_parser

T = TypeVar("T")


class JsonDocument:
    """A mutable JSON object wrapper - the document model every ``DatabaseEntry`` is built
    on - offering chained ``append`` calls, typed getters and file round-tripping.

    The Java original wraps a Gson ``JsonObject``; this edition wraps a plain ``dict``
    holding JSON-native values only (``dict``/``list``/``str``/``int``/``float``/``bool``/
    ``None``). Arbitrary objects handed to :meth:`append` are converted on the way in (see
    ``json.adapter``), never stored live - so a document is always serializable as-is,
    exactly like the Java edition.

    Identity semantics are deliberately inherited from ``object`` (no ``__eq__``): the
    Java class never defined ``equals``/``hashCode`` either, and
    :meth:`get_meta_data_set` relies on documents being hashable by identity.
    """

    def __init__(self, source: Any = None, value: Any = ...) -> None:
        """Builds a document from any of the shapes the Java constructors accepted.

        - no arguments: an empty document
        - ``bytes``: UTF-8 encoded JSON to parse
        - ``str``: JSON text to parse - unless ``value`` is also given, in which case
          ``source`` is a key and this is the ``(key, value)`` convenience constructor
        - ``dict``: adopted directly as the backing mapping (not copied, matching the
          Java ``JsonObject`` constructor's by-reference semantics)
        - ``JsonDocument``: adopts the other document's backing mapping by reference
        - ``os.PathLike``: a JSON file to read
        - text or binary stream: JSON content to read

        Raises:
            TypeError: If ``source`` is of an unsupported type.
        """
        self.json_object: dict[str, Any] = {}

        if source is None:
            return
        if isinstance(source, str) and value is not ...:
            self.append(source, value)
            return
        if isinstance(source, JsonDocument):
            self.json_object = source.json_object
            return
        if isinstance(source, dict):
            self.json_object = source
            return
        if isinstance(source, (bytes, bytearray)):
            self.json_object = dict(document_json_parser.parse_string(bytes(source).decode("utf-8")))
            return
        if isinstance(source, str):
            self.json_object = dict(document_json_parser.parse_string(source))
            return
        if isinstance(source, os.PathLike):
            with open(source, encoding="utf-8") as reader:
                self.json_object = dict(document_json_parser.parse_reader(reader))
            return
        if isinstance(source, io.TextIOBase):
            self.json_object = dict(document_json_parser.parse_reader(source))
            return
        if hasattr(source, "read"):
            content = source.read()
            if isinstance(content, (bytes, bytearray)):
                content = bytes(content).decode("utf-8")
            self.json_object = dict(document_json_parser.parse_string(content))
            return

        raise TypeError(f"@JsonDocument: unsupported source type {type(source).__name__}")

    # ------------------------------------------------------------------ append

    def append(self, key: Any = None, value: Any = ...) -> JsonDocument:
        """Appends a value under ``key``, or merges a whole mapping/document/stream.

        One method standing in for every Java ``append`` overload: called with
        ``(key, value)`` it stores the converted value under ``key`` (``bytes`` values are
        stored as their signed big-endian integer, matching Java's ``BigInteger`` storage
        that :meth:`get_binary` decodes); called with a single ``dict``, ``JsonDocument``
        or readable stream it merges that argument's entries into this document. ``None``
        arguments are ignored, matching the Java overloads' null-tolerance.

        Returns:
            This document, for chaining.
        """
        if key is None:
            return self

        if value is ...:
            if isinstance(key, JsonDocument):
                self.json_object.update(key.json_object)
            elif isinstance(key, Mapping):
                for entry_key, entry in key.items():
                    self.json_object[str(entry_key)] = to_jsonable(entry)
            elif hasattr(key, "read"):
                self.append(JsonDocument(key))
            else:
                raise TypeError(f"@JsonDocument.append: cannot merge {type(key).__name__}")
            return self

        if not isinstance(key, str):
            raise TypeError(f"@JsonDocument.append: key must be a str, got {type(key).__name__}")
        if isinstance(value, (bytes, bytearray)):
            self.json_object[key] = int.from_bytes(bytes(value), "big", signed=True)
        else:
            self.json_object[key] = to_jsonable(value)
        return self

    # ------------------------------------------------------------ nested access

    def get_meta_data(self, key: str) -> JsonDocument | None:
        """Returns the nested document stored under ``key``, or ``None`` if the key is
        absent or does not hold a JSON object."""
        value = self.json_object.get(key)
        return JsonDocument(value) if isinstance(value, dict) else None

    def get_meta_data_set(self) -> set[JsonDocument]:
        """Returns the value of every top-level entry of this document as a
        ``JsonDocument`` (non-object values become empty documents, mirroring the Java
        behavior of wrapping every entry's element)."""
        return {
            JsonDocument(value) if isinstance(value, dict) else JsonDocument()
            for value in self.json_object.values()
        }

    def remove(self, key: str) -> JsonDocument:
        """Removes the entry with the given key, if present. Returns this document."""
        self.json_object.pop(key, None)
        return self

    def clear(self) -> JsonDocument:
        """Removes all entries from this document. Returns this document."""
        self.json_object.clear()
        return self

    # ---------------------------------------------------------------- getters
    #
    # The Java edition exposes one getter per JVM primitive width (byte/short/int/long,
    # float/double). Python has a single int and a single float, but every alias is kept
    # so ported consumer code compiles - er, runs - unchanged.

    def get_integer(self, key: str) -> int:
        """Returns the value under ``key`` as an ``int``."""
        return int(self.json_object[key])

    def get_long(self, key: str) -> int:
        """Alias of :meth:`get_integer`; Python has no separate 64-bit integer type."""
        return self.get_integer(key)

    def get_short(self, key: str) -> int:
        """Alias of :meth:`get_integer`; Python has no separate 16-bit integer type."""
        return self.get_integer(key)

    def get_byte(self, key: str) -> int:
        """Alias of :meth:`get_integer`; Python has no separate 8-bit integer type."""
        return self.get_integer(key)

    def get_double(self, key: str) -> float:
        """Returns the value under ``key`` as a ``float``."""
        return float(self.json_object[key])

    def get_float(self, key: str) -> float:
        """Alias of :meth:`get_double`; Python has no separate 32-bit float type."""
        return self.get_double(key)

    def get_boolean(self, key: str) -> bool:
        """Returns the value under ``key`` as a ``bool``."""
        return bool(self.json_object[key])

    def get_string(self, key: str) -> str:
        """Returns the value under ``key`` as a ``str``."""
        return str(self.json_object[key])

    def get_char(self, key: str) -> str:
        """Returns the first character of the value under ``key``, matching Java's
        ``getChar`` reading ``getAsString().charAt(0)``."""
        return str(self.json_object[key])[0]

    def get_big_decimal(self, key: str) -> Decimal:
        """Returns the value under ``key`` as a lossless ``Decimal``."""
        return Decimal(str(self.json_object[key]))

    def get_big_integer(self, key: str) -> int:
        """Returns the value under ``key`` as an arbitrary-precision ``int`` (which every
        Python ``int`` already is; kept for contract parity)."""
        return int(self.json_object[key])

    def get_binary(self, key: str) -> bytes:
        """Returns the value under ``key`` decoded back into ``bytes``.

        Inverse of the ``bytes`` branch of :meth:`append`: the stored signed big-endian
        integer is decoded with the same minimal-length semantics as Java's
        ``BigInteger.toByteArray()``.

        Raises:
            KeyError: If the key does not exist.
        """
        value = int(self.json_object[key])
        return value.to_bytes(value.bit_length() // 8 + 1, "big", signed=True)

    def get(
        self,
        key: str,
        target_type: type[T] | None = None,
        default: T | None = None,
        predicate: Callable[[T], bool] | None = None,
    ) -> T | Any | None:
        """Deserializes the value under ``key`` into ``target_type``.

        One method standing in for Java's ``get(key, Class)``, ``get(key, Type, def)`` and
        ``get(key, Type, def, Predicate)`` overloads: absent keys return ``default``
        (``None`` unless given), and a ``predicate`` - when supplied - must accept the
        deserialized value for it to be returned instead of ``default``. Called without a
        ``target_type`` it returns the raw JSON-native value, standing in for the
        deprecated raw-``JsonElement`` accessor.
        """
        if key not in self.json_object:
            return default
        result = from_jsonable(self.json_object[key], target_type)
        if predicate is not None and not predicate(result):
            return default
        return result

    # -------------------------------------------------------------------- I/O

    def write(self, path: str | os.PathLike[str]) -> bool:
        """Writes this document as pretty-printed JSON to ``path``, recreating any
        existing file first.

        Returns:
            ``True`` if the write succeeded, ``False`` otherwise - failures are swallowed
            rather than raised, matching the Java edition's error handling.
        """
        try:
            destination = Path(path)
            if destination.parent != Path():
                destination.parent.mkdir(parents=True, exist_ok=True)
            with open(destination, "w", encoding="utf-8") as writer:
                writer.write(self.to_json())
            return True
        except OSError:
            return False

    def contains(self, key: str | None) -> bool:
        """Returns whether this document has an entry under ``key``."""
        return key is not None and key in self.json_object

    def get_keys(self) -> set[str]:
        """Returns the set of this document's top-level keys."""
        return set(self.json_object.keys())

    def copy(self) -> JsonDocument:
        """Returns a deep copy of this document, so mutations on the copy can never leak
        into this instance."""
        return JsonDocument(_json.loads(self.to_json()))

    def as_map(self) -> dict[str, Any]:
        """Returns a shallow view of this document's entries as a ``dict`` of raw
        JSON-native values."""
        return dict(self.json_object)

    def to_json(self) -> str:
        """Serializes this document to its pretty-printed JSON string representation."""
        return _json.dumps(self.json_object, indent=2, ensure_ascii=False)

    def to_bytes(self) -> bytes:
        """Serializes this document to JSON and encodes it as UTF-8 bytes."""
        return self.to_json().encode("utf-8")

    def __contains__(self, key: str) -> bool:
        return self.contains(key)

    def __repr__(self) -> str:
        return f"JsonDocument({self.json_object!r})"

    @staticmethod
    def load(input: str | os.PathLike[str]) -> JsonDocument:
        """Loads and parses a ``JsonDocument`` from the file at the given path. If the
        file cannot be read or parsed, an empty document is returned instead - matching
        the Java edition's swallow-and-fall-back error handling."""
        try:
            with open(input, encoding="utf-8") as reader:
                return JsonDocument(dict(document_json_parser.parse_reader(reader)))
        except (OSError, ValueError):
            return JsonDocument()
