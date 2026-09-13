"""Mirror of ``de.lino.database.database.nosql.csv.CSVDatabaseProvider``."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.csv.csv_database_section import CSVDatabaseSection

_EXTENSION = ".csv"
"""The file extension every section's file carries."""


class CSVDatabaseProvider(AbstractLazyDatabaseProvider):
    """The CSV-file-based ``DatabaseProvider``: every section is one ``<name>.csv`` file
    directly under the credentials' file repository, via ``CSVDatabaseSection``. Section
    lifecycle and caching live in ``AbstractLazyDatabaseProvider``; this class only
    supplies the file-level storage operations - listing ``.csv`` files, constructing a
    section, deleting a file. Discovers only names at construction, so construction cost
    is one directory listing, independent of how much data the repository holds."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__()
        self.repository = Path(credentials.file_repository)
        self.reload()

    def shutdown(self) -> None:
        pass

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Lists the repository's ``.csv`` files, (re-)creating the repository root first
        so a fresh installation starts from an existing, empty directory rather than
        failing to list a missing one; each file name minus the extension is one section
        name."""
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.create_directory(self.repository)

        for path in self.repository.glob(f"*{_EXTENSION}"):
            if path.is_file():
                consumer(path.name[: -len(_EXTENSION)])

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return CSVDatabaseSection(name, self.repository / f"{name}{_EXTENSION}", config)

    def drop_section_remote(self, name: str) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_file(self.repository / f"{name}{_EXTENSION}")
