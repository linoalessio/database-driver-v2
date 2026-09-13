"""Mirror of ``de.lino.database.database.entity.DatabaseEntry``."""

from __future__ import annotations

from dataclasses import dataclass

from database_driver.api.json.json_document import JsonDocument


@dataclass(frozen=True, slots=True)
class DatabaseEntry:
    """A single record stored inside a ``DatabaseSection``, consisting of a unique
    primary key and its associated ``JsonDocument`` content.

    By convention, the wrapped document stores its actual payload under the ``"data"``
    key, which can be retrieved directly via :meth:`get_meta_data`.

    Attributes:
        id: The primary key that uniquely identifies this entry within its section.
        document: The full document backing this entry, including its ``"data"`` envelope.
    """

    id: str
    document: JsonDocument

    def get_meta_data(self) -> JsonDocument | None:
        """Returns the entry's payload: the nested document under the ``"data"`` key, or
        ``None`` if the backing document has no such object."""
        return self.document.get_meta_data("data")
