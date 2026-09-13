"""Mirror of ``de.lino.database.json.file.FileProvider``."""

from __future__ import annotations

import os
from abc import ABC, abstractmethod
from collections.abc import Collection
from typing import ClassVar

PathLike = str | os.PathLike[str]


class FileProvider(ABC):
    """Abstraction over the filesystem operations used throughout the driver (creating,
    deleting, renaming and copying files and directories), so that ``JsonDocument`` and
    the various providers do not depend on a concrete filesystem implementation directly.

    A single, globally accessible instance is installed via :meth:`set_instance` and
    retrieved through :meth:`get_instance`.

    The Java edition carries ``File``/``Path`` overload pairs for several operations;
    Python needs only one method per operation, since every path parameter accepts any
    ``os.PathLike`` alongside ``str``.
    """

    _instance: ClassVar[FileProvider | None] = None

    @classmethod
    def get_instance(cls) -> FileProvider | None:
        """Returns the globally accessible instance, or ``None`` if none was installed."""
        return FileProvider._instance

    @classmethod
    def set_instance(cls, instance: FileProvider) -> None:
        """Installs the given provider as the globally accessible instance returned by
        :meth:`get_instance`."""
        FileProvider._instance = instance

    @abstractmethod
    def delete_file(self, file: PathLike) -> None:
        """Deletes the given file, if it exists."""

    @abstractmethod
    def create_file(self, file: PathLike) -> None:
        """Creates the given file if it does not exist yet."""

    @abstractmethod
    def update_file(self, file: PathLike) -> None:
        """Recreates the given file: deletes it if present, then creates it anew."""

    @abstractmethod
    def rename(self, file: PathLike, new_name: str) -> None:
        """Renames the given file to ``new_name`` within its directory."""

    @abstractmethod
    def create_directory(self, path: PathLike | None) -> None:
        """Creates the given directory (and any missing parents), if it does not exist."""

    @abstractmethod
    def do_copy(self, from_path: str, target: str) -> None:
        """Copies the file at ``from_path`` to ``target``, replacing any existing file."""

    @abstractmethod
    def delete_all_files_in_directory(self, dir_path: PathLike) -> None:
        """Deletes every file directly inside the given directory, leaving the directory
        itself in place."""

    @abstractmethod
    def delete_directory(self, dir_path: PathLike) -> None:
        """Deletes the given directory and everything below it."""

    @abstractmethod
    def recreate_directory(self, path: PathLike) -> None:
        """Deletes the given directory if present, then creates it empty."""

    @abstractmethod
    def copy_directory(self, path: PathLike, target: PathLike, excluded_files: Collection[str] = ()) -> None:
        """Recursively copies the directory at ``path`` to ``target``, skipping any file
        whose name is listed in ``excluded_files``."""
