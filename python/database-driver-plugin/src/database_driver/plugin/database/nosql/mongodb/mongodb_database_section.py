"""Mirror of ``de.lino.database.database.nosql.mongodb.MongoDBDatabaseSection``."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.json_document import JsonDocument

from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection


class MongoDBDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one MongoDB collection. All caching lives in
    ``AbstractCachedDatabaseSection``; this class only supplies the collection's storage
    primitives - each one a single driver call against the ``{id, data}`` document
    shape.

    Prepares the section without reading any document - MongoDB materializes a
    collection on its first write, so there is no container to create either; whether
    and when documents are loaded is the engine's decision per ``config``. Constructing
    without an explicit ``config`` mirrors the historical Java constructor: ``FULL`` and
    warmed immediately.
    """

    def __init__(self, mongo_database: Any, name: str, config: SectionConfig | None = None) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        self.collection = mongo_database[name]

        if direct:
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Iterates the collection's ``find()`` cursor, which the driver batches
        server-side - the collection is never materialized as a whole on this side of
        the wire."""
        for document in self.collection.find():
            consumer(_read_entry(document))

    def fetch_one(self, id: str) -> DatabaseEntry | None:
        """A single filtered ``find_one`` on the ``id`` field - the same field every
        write here keys on."""
        document = self.collection.find_one({"id": id})
        return None if document is None else _read_entry(document)

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        # database_entry.document is already the full "data"-enveloped document;
        # appending it as-is under another "data" key would double-wrap it, so its
        # already-unwrapped get_meta_data() is used instead, matching persist_update.
        meta = database_entry.get_meta_data()
        self.collection.insert_one({"id": database_entry.id, "data": meta.as_map() if meta else {}})

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        meta = database_entry.get_meta_data()
        self.collection.update_one(
            {"id": database_entry.id},
            {"$set": {"id": database_entry.id, "data": meta.as_map() if meta else {}}},
        )

    def persist_delete(self, id: str) -> None:
        self.collection.delete_one({"id": id})

    def count_remote(self) -> int:
        return int(self.collection.count_documents({}))

    def exists_remote(self, id: str) -> bool:
        """A filtered ``find_one`` projected down to ``_id`` only, so the presence check
        never transfers the (potentially large) ``data`` payload just to discard it."""
        return self.collection.find_one({"id": id}, projection={"_id": 1}) is not None

    def clear_remote(self) -> None:
        self.collection.delete_many({})

    def page_remote(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Pushed down entirely: the server sorts by ``id``, skips and limits, so a page
        costs one bounded query no matter how large the collection is."""
        cursor = self.collection.find().sort("id", 1).skip(offset).limit(limit)
        return [_read_entry(document) for document in cursor]


def _read_entry(document: Any) -> DatabaseEntry:
    """Parses one stored document back into a ``DatabaseEntry``, the shared row shape
    (``{id, data}``) every read here expects.

    Raises:
        NoSuchDataFound: If the document holds no ``"data"`` envelope, indicating a
            corrupted or foreign document.
    """
    if "data" not in document:
        raise NoSuchDataFound(document.get("id"))
    data = {key: value for key, value in document["data"].items()} if document["data"] else {}
    return DatabaseEntry(document["id"], JsonDocument("data", JsonDocument(data)))
