"""Mirror of ``de.lino.database.utils.export.transcript.TranscriptLegendEntry``."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class TranscriptLegendEntry:
    """One entry of a closing legend, shared by every built-in ``TranscriptExporter``
    implementation - e.g. a grading-scale key: ``label`` in a left column,
    ``description`` in a right column, aligned consistently across every entry.

    Attributes:
        label: The entry's left-column text, e.g. a grade range.
        description: The entry's right-column text, e.g. what the grade range means.
    """

    label: str
    description: str
