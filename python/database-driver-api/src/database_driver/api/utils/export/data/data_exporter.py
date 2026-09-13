"""Mirror of ``de.lino.database.utils.export.data.DataExporter``."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class DataExporter(Protocol):
    """The shared API every format-specific, flat-table exporter implements: writing an
    arbitrary data set to a file as a table, with one column per header and one table row
    per element of the data set.

    No default implementation ships with this package - a caller that needs a plain,
    ungrouped table export supplies its own ``DataExporter`` and injects it into an
    ``ExportCoordinator``. Callers can program against this contract rather than a
    concrete exporter, e.g. to export the same data set to several formats without
    branching on which one.
    """

    def export(
        self,
        rows: Sequence[Any],
        headers: Sequence[str],
        row_mapper: Callable[[Any], Sequence[str]],
        title: str,
        output: Path,
    ) -> None:
        """Writes ``rows`` to a file at ``output``, with one column per entry in
        ``headers`` and one table row per element of ``rows``.

        Args:
            rows: The data set to export, in the order it should appear.
            headers: The column headers, in display order.
            row_mapper: Turns a row into one cell value per header, in the same order as
                ``headers``.
            title: The document title.
            output: The file path the export is written to; overwritten if it already
                exists.

        Raises:
            OSError: If the export cannot be written to ``output``.
            ValueError: If ``headers`` is empty.
        """
        ...
