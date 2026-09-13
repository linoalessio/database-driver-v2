"""Mirror of ``de.lino.database.database.nosql.json.JsonDatabaseSection``."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Optional

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.exception.no_such_entry_found import NoSuchEntryFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection


class JsonDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one directory of JSON files, one file per entry,
    named ``<id>.json``. All caching lives in ``AbstractCachedDatabaseSection``; this
    class only supplies the directory's storage primitives - each one a plain file
    operation.

    Creates (if not already present) the section directory eagerly, so a freshly created
    section exists on disk (and survives a provider ``reload``) even before anything
    touches its data; no entry is read at construction - whether and when entries are
    loaded is the engine's decision per ``config``, with the owning provider triggering
    the ``FULL`` warm-up right after construction. Constructing without an explicit
    ``config`` mirrors the historical Java constructor: ``FULL`` and warmed immediately.
    """

    def __init__(self, name: str, credentials: Credentials, config: SectionConfig | None = None) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        self.credentials = credentials
        self.parent = Path(credentials.file_repository) / name

        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_directory(self.parent)

        if direct:
            # The historical loaded-once-constructed semantics for anyone instantiating
            # sections directly rather than through a provider.
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Iterates every ``*.json`` file currently in the section directory,
        (re-)creating the directory first so a freshly created section starts from an
        existing, empty directory rather than failing to list a missing one. Only files
        ending in ``.json`` are considered; a stray non-entry file (most commonly a
        filesystem-managed one such as macOS' ``.DS_Store``) is skipped rather than
        parsed as an entry, which would otherwise fail outright."""
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_directory(self.parent)

        for path in self.parent.glob("*.json"):
            consumer(self._read_entry(path.stem, path))

    def fetch_one(self, id: str) -> Optional[DatabaseEntry]:
        """A single file lookup: the entry exists exactly if its ``<id>.json`` file
        does."""
        path = self._entry_file(id)
        if not path.exists():
            return None
        return self._read_entry(id, path)

    def _read_entry(self, id: str, path: Path) -> DatabaseEntry:
        """Parses one entry's JSON file, the shared row shape (``{"id": ..., "data":
        ...}``) every read here expects.

        Raises:
            NoSuchDataFound: If the file exists but holds no ``"data"`` envelope,
                indicating a corrupted or foreign file.
        """
        document = JsonDocument.load(path)
        if not document.contains("data"):
            raise NoSuchDataFound(id)
        return DatabaseEntry(id, document)

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        # database_entry.document is already the full "data"-enveloped document;
        # appending it here as-is under another "data" key would double-wrap it, so its
        # already-unwrapped get_meta_data() is used instead - the same shape
        # persist_update below writes, so a freshly inserted entry round-trips
        # identically to a later-updated one.
        document = JsonDocument().append("id", database_entry.id).append("data", database_entry.get_meta_data())
        document.write(self._entry_file(database_entry.id))

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        """Two historical write shapes, preserved exactly: an entry whose document
        already carries its ``"id"`` (the shape ``load_all`` produces when reading a file
        back) simply replaces the stored file outright, while an entry without one (the
        shape a caller builds fresh) is *merged* - its metadata keys are added on top of
        the previously stored entry's metadata, so keys absent from the update survive.
        The previous entry is taken from the engine's in-memory view when available
        (see :meth:`AbstractCachedDatabaseSection.cached_entry`) because the in-memory
        copy is what this merge historically read, and only read from disk when nothing
        is cached."""
        if database_entry.document.contains("id"):
            self.persist_delete(database_entry.id)
            self.persist_insert(database_entry)
            return

        existing = self.cached_entry(database_entry.id) or self.fetch_one(database_entry.id)
        if existing is None:
            raise NoSuchEntryFound(database_entry.id)

        data = existing.get_meta_data()
        assert data is not None
        update_meta = database_entry.get_meta_data()
        if update_meta is not None:
            for key, value in update_meta.as_map().items():
                data.json_object[key] = value

        existing.document \
            .append("id", database_entry.id) \
            .append("data", data) \
            .write(self._entry_file(database_entry.id))

    def persist_delete(self, id: str) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_file(self._entry_file(id))

    def count_remote(self) -> int:
        try:
            return sum(1 for _ in self.parent.glob("*.json"))
        except OSError:
            return 0

    def exists_remote(self, id: str) -> bool:
        return self._entry_file(id).exists()

    def clear_remote(self) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_all_files_in_directory(self.parent)

    def page_remote(self, offset: int, limit: int) -> list[DatabaseEntry]:
        """Pushed down as far as a directory store allows: the page is chosen on file
        *names* alone (one directory listing, sorted by id), and only the files actually
        inside the page window are opened and parsed - the payload cost of a page is
        O(limit), not O(section)."""
        try:
            ids = sorted(path.stem for path in self.parent.glob("*.json"))
        except OSError:
            return []

        if offset >= len(ids):
            return []

        return [self._read_entry(id, self._entry_file(id)) for id in ids[offset : offset + limit]]

    def _entry_file(self, id: str) -> Path:
        """Resolves the file an entry with ``id`` is stored in - the single naming rule
        (``<parent>/<id>.json``) every primitive above shares."""
        return self.parent / f"{id}.json"
