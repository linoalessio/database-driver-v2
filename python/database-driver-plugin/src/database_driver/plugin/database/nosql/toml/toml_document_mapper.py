"""Mirror of ``de.lino.database.database.nosql.toml.TomlDocumentMapper``."""

from __future__ import annotations

import math
import tomllib
from typing import Any

import tomli_w
from database_driver.api.json.json_document import JsonDocument


def to_toml(document: JsonDocument) -> str:
    """Renders ``document`` as TOML text.

    The JSON and TOML models do not overlap perfectly, and this mapper resolves every
    mismatch in one place - so the TOML section's storage primitives stay plain file
    operations - with the following deliberate rules, identical to the Java edition's:

    - **``None`` values are dropped on write.** TOML has no ``null`` literal at all;
      silently omitting the key (rather than failing the whole write) mirrors how
      ``JsonDocument.append`` already treats a ``None`` value as "nothing to store".
    - **Integral JSON numbers stay integral.** The Java edition needs explicit handling
      because Gson reads every number back as ``double``; Python's ``json`` module keeps
      ``int`` and ``float`` distinct natively, so ``age = 23`` round-trips as a TOML
      integer without extra work - the rule holds by construction.
    - **TOML's own restrictions apply to what documents can be stored.** Special
      floating-point values (``NaN``, infinities) are rejected here at write time rather
      than producing a file that can never be parsed back, and heterogeneous arrays are
      constrained by the writer itself.

    Raises:
        ValueError: If the document holds a value TOML cannot express.
    """
    return tomli_w.dumps(_to_toml_value(document.json_object))


def from_toml(toml: str) -> JsonDocument:
    """Parses TOML text back into a ``JsonDocument``, the inverse of :func:`to_toml`.
    TOML types map naturally onto JSON ones (tables to objects, arrays to arrays,
    integers and floats to numbers); the one one-way street is a TOML datetime, which
    JSON lacks and which comes back as its serialized string form.

    Raises:
        tomllib.TOMLDecodeError: If ``toml`` is not valid TOML.
    """
    parsed = tomllib.loads(toml)
    return JsonDocument(_from_toml_value(parsed))


def _to_toml_value(value: Any) -> Any:
    """Recursively converts a JSON-native tree into the shape the TOML writer expects,
    enforcing the mapper rules: ``None``s are dropped, non-finite floats are rejected."""
    if isinstance(value, dict):
        converted = {}
        for key, entry in value.items():
            entry_value = _to_toml_value(entry)
            if entry_value is not None:
                converted[key] = entry_value
        return converted
    if isinstance(value, list):
        return [entry for entry in (_to_toml_value(item) for item in value) if entry is not None]
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError(f"@TomlDocumentMapper: TOML cannot express {value}")
    return value


def _from_toml_value(value: Any) -> Any:
    """Recursively converts a parsed TOML tree into JSON-native values; datetimes and
    dates - which JSON lacks - come back as their serialized string form, matching the
    Java edition's round-trip behavior."""
    if isinstance(value, dict):
        return {key: _from_toml_value(entry) for key, entry in value.items()}
    if isinstance(value, list):
        return [_from_toml_value(entry) for entry in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)  # TOML datetime/date/time: JSON has no counterpart
