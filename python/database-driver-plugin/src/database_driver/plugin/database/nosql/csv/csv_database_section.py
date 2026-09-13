"""Mirror of ``de.lino.database.database.nosql.csv.CSVDatabaseSection``."""

from __future__ import annotations

import base64
import os
import traceback
from collections.abc import Callable
from pathlib import Path
from typing import Optional

from database_driver.api.database.entity.database_entry import DatabaseEntry
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.api.json.json_document import JsonDocument
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection


class CSVDatabaseSection(AbstractCachedDatabaseSection):
    """The ``DatabaseSection`` backing one CSV file, one row per entry as
    ``<base64 id>,<base64 data>`` - both columns Base64-encoded so neither an entry's id
    nor its serialized document can ever contain a comma, quote or newline that would
    otherwise need RFC 4180-style escaping to round-trip correctly. All caching lives in
    ``AbstractCachedDatabaseSection``; this class only supplies the file's storage
    primitives.

    A single CSV file has no notion of an in-place row update, so ``persist_update`` and
    ``persist_delete`` rewrite the whole file rather than editing a single line, while
    ``persist_insert`` just appends; for the same reason every point primitive
    (``fetch_one``, ``exists_remote``, ``count_remote``) is a scan over the file's lines
    rather than a true point lookup - a full in-memory cache remains the natural fit for
    this store.
    """

    def __init__(self, name: str, file: Path, config: SectionConfig | None = None) -> None:
        direct = config is None
        super().__init__(name, config or SectionConfig.full())
        self.file = file

        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_file(self.file)

        if direct:
            self.warm_up()

    def load_all(self, consumer: Callable[[DatabaseEntry], None]) -> None:
        """Streams the file line by line rather than reading every line into memory
        first, (re-)creating the file beforehand so a freshly created section starts from
        an existing, empty file rather than failing to read a missing one. Blank lines
        are skipped, matching what ``persist_insert``'s trailing line separator leaves
        behind."""
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_file(self.file)

        try:
            with open(self.file, encoding="utf-8") as reader:
                for line in reader:
                    line = line.rstrip("\r\n")
                    if not line.strip():
                        continue
                    consumer(_parse_row(line))
        except OSError:
            traceback.print_exc()

    def fetch_one(self, id: str) -> Optional[DatabaseEntry]:
        """A bounded scan over the file's rows, comparing decoded ids - a single CSV file
        offers no cheaper point lookup; see the class documentation."""
        for line in _read_lines(self.file):
            if not line.strip():
                continue
            if _row_id(line) == id:
                return _parse_row(line)
        return None

    def persist_insert(self, database_entry: DatabaseEntry) -> None:
        try:
            with open(self.file, "a", encoding="utf-8") as writer:
                writer.write(_row(database_entry) + os.linesep)
        except OSError:
            traceback.print_exc()

    def persist_update(self, database_entry: DatabaseEntry) -> None:
        self._rewrite(database_entry.id, database_entry)

    def persist_delete(self, id: str) -> None:
        self._rewrite(id, None)

    def count_remote(self) -> int:
        """A scan counting the file's non-blank lines - each one is exactly one row."""
        return sum(1 for line in _read_lines(self.file) if line.strip())

    def exists_remote(self, id: str) -> bool:
        """A bounded scan over the file's rows, comparing decoded ids only - the row's
        data column is never parsed here."""
        return any(line.strip() and _row_id(line) == id for line in _read_lines(self.file))

    def clear_remote(self) -> None:
        try:
            with open(self.file, "w", encoding="utf-8"):
                pass
        except OSError:
            traceback.print_exc()

    def _rewrite(self, row_id: str, replacement: DatabaseEntry | None) -> None:
        """Rewrites the file without the row stored under ``row_id``, appending
        ``replacement``'s row instead if one is given - the single read-modify-write
        shape ``persist_update`` (replace) and ``persist_delete`` (drop) share, since a
        CSV file cannot edit one line in place."""
        kept = [line for line in _read_lines(self.file) if line.strip() and _row_id(line) != row_id]
        if replacement is not None:
            kept.append(_row(replacement))
        try:
            with open(self.file, "w", encoding="utf-8") as writer:
                for line in kept:
                    writer.write(line + os.linesep)
        except OSError:
            traceback.print_exc()


def _row_id(line: str) -> str:
    """Decodes a row's id column without touching its data column, so id-only scans skip
    the far larger document payload."""
    return _decode(line[: line.index(",")])


def _parse_row(line: str) -> DatabaseEntry:
    """Parses one CSV row back into a ``DatabaseEntry``, the inverse of :func:`_row`."""
    separator = line.index(",")
    id = _decode(line[:separator])
    data = base64.b64decode(line[separator + 1 :])
    return DatabaseEntry(id, JsonDocument(data))


def _row(database_entry: DatabaseEntry) -> str:
    """Builds an entry's CSV row: its Base64-encoded id, a comma, and its Base64-encoded
    serialized document, without a trailing line terminator."""
    return _encode(database_entry.id) + "," + base64.b64encode(database_entry.document.to_bytes()).decode("ascii")


def _encode(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def _decode(value: str) -> str:
    return base64.b64decode(value).decode("utf-8")


def _read_lines(file: Path) -> list[str]:
    """Reads every line of ``file``, or an empty list if it cannot be read."""
    try:
        return file.read_text(encoding="utf-8").splitlines()
    except OSError:
        traceback.print_exc()
        return []
