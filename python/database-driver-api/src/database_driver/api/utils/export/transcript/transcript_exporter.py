"""Mirror of ``de.lino.database.utils.export.transcript.TranscriptExporter``."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Protocol, runtime_checkable

from database_driver.api.utils.export.transcript.format.page_layout import PageLayout
from database_driver.api.utils.export.transcript.transcript_legend_entry import TranscriptLegendEntry
from database_driver.api.utils.export.transcript.transcript_section import TranscriptSection


@runtime_checkable
class TranscriptExporter(Protocol):
    """The shared shape of a grouped, "transcript-style" exporter: writing an
    already-grouped, already-formatted data set - ``TranscriptSection`` groups under a
    shared set of column headers, plus an optional closing ``TranscriptLegendEntry``
    legend - to a file. The transcript counterpart of ``DataExporter``'s flat-table
    contract.

    ``ExportCoordinator``'s built-in PDF/Excel/CSV/XML/JSON/DOCX exporters implement this
    contract directly (each private to the coordinator, only reachable through it) - see
    ``ExportCoordinator.export_transcript``, which auto-detects between them; as a
    structural ``Protocol`` it can just as well be satisfied by any object with a
    matching ``export`` method for a one-off implementation.
    """

    def export(
        self,
        document_title: str,
        column_headers: Sequence[str],
        sections: Sequence[TranscriptSection],
        legend_title: str,
        legend_entries: Sequence[TranscriptLegendEntry],
        page_layout: PageLayout,
        output: Path,
    ) -> None:
        """Writes ``sections`` to a file at ``output``, one column per entry in
        ``column_headers``, with an optional closing legend.

        Args:
            document_title: The document title shown on every page/sheet.
            column_headers: The column headers, in display order.
            sections: The grouped rows to write, in the order they should appear.
            legend_title: The closing legend's heading; ignored if ``legend_entries`` is
                empty.
            legend_entries: The closing legend's entries, or empty to omit it.
            page_layout: The page size and orientation to render the export at.
            output: The file path the export is written to; overwritten if it already
                exists.

        Raises:
            OSError: If the export cannot be written to ``output``.
        """
        ...
