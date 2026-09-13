"""Mirror of ``de.lino.database.database.nosql.toml.TOMLDatabaseSection``."""

from __future__ import annotations

import traceback
from collections.abc import Callable
from pathlib import Path
from typing import Optional

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.exception.no_such_data_found import NoSuchDataFound
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.nosql.toml import toml_document_mapper

_EXTENSION = ".toml"
"""The file extension every entry's file carries."""


class TOMLDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one directory of TOML files, one file per entry,
    named ``<id>.toml`` - the JSON file store's layout with human-editable TOML as the
    on-disk syntax, for data that doubles as configuration. All caching lives in
    ``AbstractCachedDatabaseSection``; this class only supplies the directory's storage
    primitives, with every JSON-to-TOML modelling decision delegated to
    ``toml_document_mapper`` (read its rules before storing unusual documents - TOML
    cannot hold ``None``s or mixed-type arrays).

    Each file carries the entry's full envelope - a top-level ``id`` key plus a
    ``[data]`` table::

        id = "Lino"

        [data]
        name = "lino"
        age = 23

    Unlike the JSON store, whose ``persist_update`` historically merges into the
    previously stored document, an update here simply *replaces* the entry's file with
    the given entry's state - the semantics ``DatabaseSection.update`` documents, with no
    legacy behavior to preserve in a new backend.
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
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Iterates every ``.toml`` file currently in the section directory,
        (re-)creating the directory first so a freshly created section starts from an
        existing, empty directory rather than failing to list a missing one. Files with
        any other extension (editor backups, ``.DS_Store`` ...) are ignored rather than
        parsed as entries."""
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_directory(self.parent)

        for path in self.parent.glob(f"*{_EXTENSION}"):
            consumer(self._read_entry(path.stem, path))

    def fetch_one(self, id: str) -> Optional[DatabaseEntry]:
        """A single file lookup: the entry exists exactly if its ``<id>.toml`` file
        does."""
        path = self._entry_file(id)
        if not path.exists():
            return None
        return self._read_entry(id, path)

    def _read_entry(self, id: str, path: Path) -> DatabaseEntry:
        """Parses one entry's TOML file into the same ``{id, data}``-enveloped
        ``DatabaseEntry`` shape the JSON store produces, so a consumer switching stores
        sees identical entries.

        Raises:
            NoSuchDataFound: If the file is not parseable TOML or holds no ``[data]``
                table - either way not an entry of this store; the same corruption
                contract as the JSON store, so unreadable files surface uniformly rather
                than as backend-specific parse errors.
        """
        try:
            document = toml_document_mapper.from_toml(path.read_text(encoding="utf-8"))
        except Exception:
            document = JsonDocument()

        if not document.contains("data"):
            raise NoSuchDataFound(id)

        return DatabaseEntry(id, document)

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        self._write_entry(database_entry)

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        """A plain replace of the entry's file - see the class documentation for why this
        backend has no merge semantics."""
        self._write_entry(database_entry)

    def persist_delete(self, id: str) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_file(self._entry_file(id))

    def count_remote(self) -> int:
        try:
            return sum(1 for _ in self.parent.glob(f"*{_EXTENSION}"))
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
            ids = sorted(path.stem for path in self.parent.glob(f"*{_EXTENSION}"))
        except OSError:
            return []

        if offset >= len(ids):
            return []

        return [self._read_entry(id, self._entry_file(id)) for id in ids[offset : offset + limit]]

    def _write_entry(self, database_entry: DatabaseEntry) -> None:
        """Writes the entry's file in the class-documented shape (top-level ``id``,
        ``[data]`` table) - the single write path ``persist_insert`` and
        ``persist_update`` share."""
        # database_entry.document is already the full "data"-enveloped document;
        # appending it as-is under another "data" key would double-wrap it, so its
        # already-unwrapped get_meta_data() is used instead, matching the other stores.
        document = JsonDocument().append("id", database_entry.id).append("data", database_entry.get_meta_data())
        try:
            self._entry_file(database_entry.id).write_text(toml_document_mapper.to_toml(document), encoding="utf-8")
        except OSError:
            traceback.print_exc()

    def _entry_file(self, id: str) -> Path:
        """Resolves the file an entry with ``id`` is stored in - the single naming rule
        (``<parent>/<id>.toml``) every primitive above shares."""
        return self.parent / f"{id}{_EXTENSION}"
