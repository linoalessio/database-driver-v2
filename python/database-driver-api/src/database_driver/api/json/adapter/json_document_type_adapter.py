"""Mirror of ``de.lino.database.json.adapter.JsonDocumentTypeAdapter``.

The Java original is a Gson ``TypeAdapter`` registered on ``JsonDocument``'s ``Gson``
instance so nested ``JsonDocument`` fields on arbitrary classes are transparently written
as plain JSON objects instead of being serialized as beans. Python has no Gson; the same
responsibility - "turn any value the driver may be handed into something the stdlib JSON
encoder can write, and back" - lives here as two module-level functions that
``JsonDocument`` delegates to.
"""

from __future__ import annotations

import dataclasses
from decimal import Decimal
from enum import Enum
from typing import Any


def to_jsonable(value: Any) -> Any:
    """Converts ``value`` into a JSON-native structure (dict/list/str/int/float/bool/None).

    Handles, in order: values that are already JSON-native, ``JsonDocument`` (unwrapped to
    its underlying mapping, like the Java adapter delegating to the wrapped
    ``JsonObject``), ``Decimal`` (kept lossless as a string - Java's Gson writes
    ``BigDecimal`` as a JSON number of arbitrary precision, which Python's ``float`` could
    not represent), ``Enum`` (by name, matching Gson's default enum handling), dataclasses,
    and finally any object with a ``__dict__`` (field-by-field, matching Gson's reflective
    bean serialization).

    Raises:
        TypeError: If ``value`` is of a type that cannot be represented as JSON.
    """
    # Local import: JsonDocument imports this module, so the reverse import must be lazy.
    from database_driver.api.json.json_document import JsonDocument

    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, JsonDocument):
        return {key: to_jsonable(entry) for key, entry in value.json_object.items()}
    if isinstance(value, dict):
        return {str(key): to_jsonable(entry) for key, entry in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_jsonable(entry) for entry in value]
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, Enum):
        return value.name
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {field.name: to_jsonable(getattr(value, field.name)) for field in dataclasses.fields(value)}
    if hasattr(value, "__dict__"):
        return {key: to_jsonable(entry) for key, entry in vars(value).items() if not key.startswith("_")}

    raise TypeError(f"@JsonDocumentTypeAdapter.to_jsonable: cannot serialize {type(value).__name__} to JSON")


def from_jsonable(value: Any, target_type: type[Any] | None) -> Any:
    """Reconstructs an instance of ``target_type`` from a JSON-native ``value``.

    The inverse of :func:`to_jsonable`, standing in for Gson's ``fromJson``: a ``None``
    target (or a value already of the target type) passes through, a ``JsonDocument``
    target wraps the mapping, an ``Enum`` target resolves by name, a dataclass target is
    built field by field, and any other class is first tried through its constructor as
    keyword arguments and otherwise instantiated without calling ``__init__`` with its
    ``__dict__`` filled directly - the closest Python analogue of Gson's
    constructor-bypassing reflective instantiation, without which entities whose
    ``__init__`` demands every field could never be read back generically.
    """
    from database_driver.api.json.json_document import JsonDocument

    if target_type is None or value is None:
        return value
    if isinstance(value, target_type) and not isinstance(value, dict):
        return value
    if target_type is JsonDocument and isinstance(value, dict):
        return JsonDocument(value)
    if issubclass(target_type, Enum) and isinstance(value, str):
        return target_type[value]
    if target_type is Decimal:
        return Decimal(value)
    if isinstance(value, dict):
        if dataclasses.is_dataclass(target_type):
            names = {field.name for field in dataclasses.fields(target_type)}
            return target_type(**{key: entry for key, entry in value.items() if key in names})
        try:
            return target_type(**value)
        except TypeError:
            instance = object.__new__(target_type)
            instance.__dict__.update(value)
            return instance
    return target_type(value)
