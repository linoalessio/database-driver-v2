"""Mirror of ``de.lino.database.utils.export.ExportType``."""

from __future__ import annotations

from enum import Enum
from pathlib import Path


class ExportType(Enum):
    """The transcript file formats ``ExportCoordinator`` ships a built-in
    ``TranscriptExporter`` for.

    Each constant carries the file extension its format is conventionally saved with; see
    :meth:`from_suffix`, which ``ExportCoordinator.export_transcript`` uses to auto-detect
    a call's output format from its file extension, and :meth:`adjust_suffix_to_file`,
    its inverse, for building such a path in the first place.
    """

    PDF = "pdf"
    """Portable Document Format."""

    EXCEL = "xlsx"
    """Office Open XML Workbook (.xlsx)."""

    CSV = "csv"
    """Comma-separated values."""

    XML = "xml"
    """Extensible Markup Language."""

    JSON = "json"
    """JavaScript Object Notation."""

    DOCX = "docx"
    """Office Open XML Document (.docx)."""

    @property
    def suffix(self) -> str:
        """The file extension this format is conventionally saved with, without a leading
        dot (e.g. ``"pdf"``)."""
        return self.value

    def adjust_suffix_to_file(self, path: Path) -> Path:
        """Appends :attr:`suffix` to ``path``, separated by a dot, e.g.
        ``ExportType.PDF.adjust_suffix_to_file(Path("transcript"))`` returns a path for
        ``"transcript.pdf"``."""
        return Path(f"{path}.{self.suffix}")

    @staticmethod
    def from_suffix(file: str) -> ExportType | None:
        """Resolves the ``ExportType`` whose :attr:`suffix` matches ``file``'s file
        extension (case-insensitively), e.g. ``from_suffix("transcript.PDF")`` returns
        :attr:`PDF`.

        Returns:
            The matching ``ExportType``, or ``None`` if ``file`` has no extension or it
            matches none of this enum's suffixes - standing in for the Java edition's
            ``Optional``.
        """
        dot_index = file.rfind(".")
        if dot_index < 0 or dot_index == len(file) - 1:
            return None
        extension = file[dot_index + 1 :].lower()
        return next((export_type for export_type in ExportType if export_type.suffix == extension), None)
