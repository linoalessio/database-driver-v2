"""Mirror of ``de.lino.database.database.nosql.toml.TOMLDatabaseProvider``."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from database_driver.api.database.auth.credentials import Credentials
from database_driver.api.database.section_config import SectionConfig
from database_driver.api.json.file.file_provider import FileProvider
from database_driver.plugin.database.abstract_cached_database_section import AbstractCachedDatabaseSection
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.nosql.toml.toml_database_section import TOMLDatabaseSection


class TOMLDatabaseProvider(AbstractLazyDatabaseProvider):
    """The TOML-file-based ``DatabaseProvider``: every section is a subdirectory of the
    credentials' file repository, holding one TOML file per entry, via
    ``TOMLDatabaseSection`` - the JSON file store's layout with hand-editable TOML files.
    Section lifecycle and caching live in ``AbstractLazyDatabaseProvider``; this class
    only supplies the directory-level storage operations.

    Discovery is by directory alone, exactly like the JSON store's - the two cannot tell
    each other's section directories apart - so give each file-based provider its own
    repository root rather than pointing a JSON and a TOML provider at the same
    directory."""

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
        non-directory file (most commonly macOS' ``.DS_Store``) is skipped rather than
        treated as an empty section."""
        provider = FileProvider.get_instance()
        assert provider is not None
        repository = Path(self.credentials.file_repository)
        provider.create_directory(repository)

        for path in repository.iterdir():
            if path.is_dir():
                consumer(path.name)

    def construct_section(self, name: str, config: SectionConfig) -> AbstractCachedDatabaseSection:
        return TOMLDatabaseSection(name, self.credentials, config)

    def drop_section_remote(self, name: str) -> None:
        provider = FileProvider.get_instance()
        assert provider is not None
        provider.delete_directory(Path(self.credentials.file_repository) / name)
