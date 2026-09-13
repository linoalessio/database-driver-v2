"""Mirror of ``de.lino.database.database.file.DefaultFileProvider``."""

from __future__ import annotations

import shutil
import traceback
from collections.abc import Collection
from pathlib import Path

from database_driver.api.json.file.file_provider import FileProvider, PathLike


class DefaultFileProvider(FileProvider):
    """The concrete, ``pathlib``-backed ``FileProvider``: every operation is implemented
    directly on top of :mod:`pathlib`/:mod:`shutil` - the Python counterpart of the Java
    edition's NIO implementation. Installs itself as ``FileProvider``'s singleton
    accessor on construction.

    Stateless beyond the inherited singleton instance, so every method is safe to call
    concurrently from multiple threads; thread-safety of the operations themselves is
    delegated entirely to the filesystem. Failures are printed rather than raised,
    matching the Java edition's log-and-continue convention throughout.
    """

    def __init__(self) -> None:
        FileProvider.set_instance(self)

    def delete_file(self, file: PathLike) -> None:
        try:
            Path(file).unlink(missing_ok=True)
        except OSError:
            traceback.print_exc()

    def create_file(self, file: PathLike) -> None:
        path = Path(file)
        if path.exists():
            return
        try:
            if path.parent != Path():
                path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        except OSError:
            traceback.print_exc()

    def update_file(self, file: PathLike) -> None:
        self.delete_file(file)
        self.create_file(file)

    def rename(self, file: PathLike, new_name: str) -> None:
        try:
            Path(file).rename(new_name)
        except OSError:
            traceback.print_exc()

    def create_directory(self, path: PathLike | None) -> None:
        if path is None:
            return
        try:
            Path(path).mkdir(parents=True, exist_ok=True)
        except OSError:
            traceback.print_exc()

    def do_copy(self, from_path: str, target: str) -> None:
        try:
            shutil.copyfile(from_path, target)
        except OSError:
            traceback.print_exc()

    def delete_all_files_in_directory(self, dir_path: PathLike) -> None:
        # Listed fully before deleting, rather than deleting while iterating a live
        # directory handle - the same ordering the Java edition documents for NIO.
        files: list[Path] = []
        try:
            files = [entry for entry in Path(dir_path).iterdir() if entry.is_file()]
        except OSError:
            traceback.print_exc()
        for file in files:
            self.delete_file(file)

    def delete_directory(self, dir_path: PathLike) -> None:
        try:
            shutil.rmtree(dir_path, ignore_errors=True)
        except OSError:
            traceback.print_exc()

    def recreate_directory(self, path: PathLike) -> None:
        target = Path(path)
        if target.exists():
            if target.is_dir():
                self.delete_directory(target)
            else:
                self.delete_file(target)
        self.create_directory(target)

    def copy_directory(self, path: PathLike, target: PathLike, excluded_files: Collection[str] = ()) -> None:
        excluded = set(excluded_files)
        source = Path(path)
        destination = Path(target)
        try:
            for file in source.rglob("*"):
                if not file.is_file() or file.name in excluded:
                    continue
                target_file = destination / file.relative_to(source)
                target_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file, target_file)
        except OSError:
            traceback.print_exc()
