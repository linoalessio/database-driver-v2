"""Mirror of ``de.lino.database.utils.export.archiv.ArchiveExporter``."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol, runtime_checkable


@runtime_checkable
class ArchiveExporter(Protocol):
    """The shape of a whole-directory, format-agnostic archive exporter, e.g. a
    ``DirectoryZipExporter``: writing everything under some source location to a single
    file at ``output``, as opposed to ``DataExporter`` and ``TranscriptExporter``, which
    each write one already-collected data set as a table.

    The Java original is a ``@FunctionalInterface``; the Python mirror is a structural
    ``Protocol`` for the same reason - any object with a matching ``export`` method
    satisfies it without inheriting, so a one-off implementation stays as lightweight as
    a Java lambda.
    """

    def export(self, output: Path) -> None:
        """Writes an archive to ``output``; overwritten if it already exists.

        Raises:
            OSError: If the archive cannot be written to ``output``.
        """
        ...
