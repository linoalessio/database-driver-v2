"""Mirror of ``de.lino.database.database.nosql.json.JsonDatabaseProvider``."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.json.json_database_section import JsonDatabaseSection


class JsonDatabaseProvider(AbstractLazyDatabaseProvider):
    """The file-based ``DatabaseProvider``: every section is a subdirectory of the
    credentials' file repository, holding one JSON file per entry, via
    ``JsonDatabaseSection``. Section lifecycle and caching live in
    ``AbstractLazyDatabaseProvider``; this class only supplies the directory-level
    storage operations - listing subdirectories, constructing a section, deleting a
    subdirectory. Discovers only names at construction - no section objects, no file
    contents - so construction cost is one directory listing, independent of how much
    data the repository holds."""

    def __init__(self, credentials: Credentials) -> None:
        super().__init__()
        self.credentials = credentials
        self.reload()

    def shutdown(self) -> None:
        pass

    def discover_names(self, consumer: Callable[[str], None]) -> None:
        """Lists the file repository's subdirectories, (re-)creating the repository root
        first so a fresh installation starts from an existing, empty directory rather
        than failing to list a missing one. Only directories are considered; a stray
        non-directory file sitting directly in the repository (most commonly macOS'
        ``.DS_Store``, dropped in by Finder the moment the folder is ever browsed) is
        skipped rather than treated as an empty section, which would otherwise fail
        outright - a ``JsonDatabaseSection`` always expects its own name to resolve to a
        directory it can list."""
        provider = FileProvider.get_instance()
        assert provider is not None
        repository = Path(self.credentials.file_repository)
        provider.create_directory(repository)

        for path in repository.iterdir():
            if path.is_dir():
                consumer(path.name)

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return JsonDatabaseSection(name, self.credentials, config)

    def drop_section_remote(self, name: str) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_directory(Path(self.credentials.file_repository) / name)
