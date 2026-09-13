"""Mirror of ``de.lino.database.utils.export.transcript.TranscriptSection``."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class TranscriptSection:
    """One labeled group of rows, shared by every built-in ``TranscriptExporter``
    implementation, so all six formats can be built from the same already-grouped,
    already-formatted input: printed as a heading followed by its own data rows, e.g. one
    semester's exams.

    Attributes:
        title: The section's heading, e.g. a semester's name.
        rows: The section's rows, one cell value per column header, in the same order.
    """

    title: str
    rows: list[list[str]]
