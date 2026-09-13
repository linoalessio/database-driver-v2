"""Mirror of ``de.lino.database.json.parser.DocumentJsonParser``.

The Java original wraps Gson's streaming parser in *lenient* mode. Python's stdlib parser
has no lenient mode; the driver only ever feeds this parser JSON it wrote itself, so
strict parsing is sufficient - the leniency in Java existed to survive hand-edited config
files, and a hand-edited file that no longer parses strictly should fail loudly here
rather than be silently reinterpreted.
"""

from __future__ import annotations

import json as _json
from typing import Any, Protocol


class _TextReader(Protocol):
    """The minimal reader shape :func:`parse_reader` needs - matching Java's ``Reader``
    parameter without demanding a full ``IO[str]``."""

    def read(self, size: int = ..., /) -> str: ...


def parse_string(json: str) -> Any:
    """Parses the given JSON text into its Python representation.

    Raises:
        json.JSONDecodeError: If ``json`` is not valid JSON.
    """
    return _json.loads(json)


def parse_reader(reader: _TextReader) -> Any:
    """Parses JSON from the given text stream into its Python representation.

    Raises:
        json.JSONDecodeError: If the stream's content is not valid JSON.
    """
    return _json.load(reader)
