"""Mirror of ``de.lino.database.database.nosql.rethinkdb.RethinkDBDatabaseSection``."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, Optional

from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection


class RethinkDBDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one RethinkDB table. All caching lives in
    ``AbstractCachedDatabaseSection``; this class only supplies the table's storage
    primitives - each one a single ReQL term against the ``{id, values}`` row shape.

    Prepares the section without running any query - ``db.table(name)`` only builds a
    ReQL term, and (as historically) no ``table_create`` is issued here. Constructing
    without an explicit ``config`` mirrors the historical Java constructor: ``FULL`` and
    warmed immediately.
    """

    def __init__(
        self, name: str, r: Any, connection: Any, db: Any, config: SectionConfig | None = None
    ) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        self.r = r
        self.connection = connection
        self.table = db.table(name)

        if direct:
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Iterates the table's result cursor, which the driver batches server-side -
        the table is never materialized as a whole on this side of the wire."""
        cursor = self.table.run(self.connection)
        try:
            for content in cursor:
                consumer(_read_entry(content))
        finally:
            cursor.close()

    def fetch_one(self, id: str) -> Optional[DatabaseEntry]:
        """A primary-key ``get`` - RethinkDB's native point read, keyed on the same
        ``id`` field every write here stores."""
        content = self._point_read(id)
        return None if content is None else _read_entry(content)

    def _point_read(self, id: str) -> Optional[dict[str, Any]]:
        """Runs the primary-key ``get`` shared by ``fetch_one`` and ``exists_remote``,
        unwrapping RethinkDB's "single atom, possibly null" result shape once."""
        return self.table.get(id).run(self.connection)

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        self.table.insert(_mapping(database_entry)).run(self.connection, noreply=True)

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        self.table.update(_mapping(database_entry)).run(self.connection, noreply=True)

    def persist_delete(self, id: str) -> None:
        self.table.filter({"id": id}).delete().run(self.connection, noreply=True)

    def count_remote(self) -> int:
        return int(self.table.count().run(self.connection))

    def exists_remote(self, id: str) -> bool:
        return self._point_read(id) is not None

    def clear_remote(self) -> None:
        self.table.delete().run(self.connection, noreply=True)

    def page_remote(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Pushed down entirely, ordered by the table's primary-key *index*
        (``order_by(index="id")``) rather than a plain ``order_by("id")`` - the index
        variant streams from the server in order, while the plain one would force
        RethinkDB to materialize and sort the whole table server-side (and refuse
        outright past its array size limit)."""
        cursor = self.table.order_by(index="id").skip(offset).limit(limit).run(self.connection)
        page: list[DatabaseEntry] = []
        try:
            for content in cursor:
                page.append(_read_entry(content))
        finally:
            if hasattr(cursor, "close"):
                cursor.close()
        return page


def _read_entry(content: dict[str, Any]) -> DatabaseEntry:
    """Parses one stored row back into a ``DatabaseEntry``, the shared row shape
    (``{id, values}``) every read here expects.

    Raises:
        NoSuchDataFound: If the row holds no ``"data"`` key - preserved from the
            historical loading code even though rows written by ``persist_insert`` carry
            ``"values"``, not ``"data"``; changing the check would change which stored
            rows load at all.
    """
    if "data" not in content:
        raise NoSuchDataFound(content.get("id"))
    return DatabaseEntry(content["id"], JsonDocument(content["values"]))


def _mapping(database_entry: DatabaseEntry) -> dict[str, Any]:
    """Builds the ``{id, values}`` row shape every write here stores, the entry's
    document serialized as one JSON string."""
    return {"id": database_entry.id, "values": database_entry.document.to_json()}
