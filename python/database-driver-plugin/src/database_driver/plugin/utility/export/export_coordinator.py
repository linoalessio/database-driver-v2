"""Mirror of ``de.lino.database.utility.export.ExportCoordinator``."""

from __future__ import annotations

import csv as _csv
import io
import json as _json
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, ClassVar

from database_driver.api.utils.export.archiv.archive_exporter import ArchiveExporter
from database_driver.api.utils.export.data.data_exporter import DataExporter
from database_driver.api.utils.export.export_type import ExportType
from database_driver.api.utils.export.exporter_injector import ExporterInjector
from database_driver.api.utils.export.transcript.format.page_format import PageFormat
from database_driver.api.utils.export.transcript.format.page_layout import PageLayout
from database_driver.api.utils.export.transcript.format.page_orientation import PageOrientation
from database_driver.api.utils.export.transcript.transcript_exporter import TranscriptExporter
from database_driver.api.utils.export.transcript.transcript_legend_entry import TranscriptLegendEntry
from database_driver.api.utils.export.transcript.transcript_section import TranscriptSection


class ExportCoordinator(ExporterInjector):
    """A single, generic access point for every exporter kind this module ships - flat
    tables (``DataExporter``), grouped transcripts (``TranscriptExporter``) and
    whole-directory archives (``ArchiveExporter``) - wired together purely through
    interfaces rather than any one exporter implementation.

    :meth:`export_table` and :meth:`export_archive` never call a concrete exporter
    directly; each runs through whichever ``DataExporter`` or ``ArchiveExporter`` was
    last handed to this class through ``ExporterInjector``'s setter methods -
    **interface injection**. The one ``ArchiveExporter`` implementation this module
    ships (:class:`DirectoryZipExporter`) is bundled here as a member rather than kept
    as a separate top-level class; a caller is free to inject any other implementation
    instead. No default ``DataExporter`` ships with this module; a caller that needs one
    supplies its own. Neither interface, nor this class's own inject/export methods,
    mention any application-specific type, so the whole mechanism is safe to reuse
    unchanged by any application depending on this module.

    :meth:`export_transcript` takes a different approach, with no injection involved at
    all: every call resolves its own ``TranscriptExporter`` implementation fresh,
    auto-detected from the output path's file extension via ``ExportType.from_suffix`` -
    ``.pdf``/``.xlsx``/``.csv``/``.xml``/``.json``/``.docx``.

    The PDF, Excel and DOCX exporters need their respective libraries (reportlab,
    openpyxl, python-docx - installed together via this distribution's ``export``
    extra); CSV, XML and JSON exports are pure stdlib. The library imports are local to
    each exporter, so an installation without the extra can still run the stdlib
    formats.

    Not thread-safe: swapping an exporter with another ``inject`` call while a
    previously injected one is mid-export is not synchronized against.
    """

    def __init__(self) -> None:
        self._data_exporter: DataExporter | None = None
        self._archive_exporter: ArchiveExporter | None = None

    def inject_data_exporter(self, data_exporter: DataExporter) -> None:
        if data_exporter is None:
            raise TypeError("@ExportCoordinator.inject_data_exporter: data_exporter must not be None")
        self._data_exporter = data_exporter

    def inject_archive_exporter(self, archive_exporter: ArchiveExporter) -> None:
        if archive_exporter is None:
            raise TypeError("@ExportCoordinator.inject_archive_exporter: archive_exporter must not be None")
        self._archive_exporter = archive_exporter

    def export_table(
        self,
        rows: Sequence[Any],
        headers: Sequence[str],
        row_mapper: Callable[[Any], Sequence[str]],
        title: str,
        output: Path,
    ) -> None:
        """Writes ``rows`` to a file at ``output`` through the ``DataExporter`` last
        passed to :meth:`inject_data_exporter`, with one column per entry in ``headers``
        and one table row per element of ``rows``.

        Raises:
            RuntimeError: If no ``DataExporter`` has been injected yet.
        """
        _require_injected(self._data_exporter, "DataExporter")
        assert self._data_exporter is not None
        self._data_exporter.export(rows, headers, row_mapper, title, output)

    def export_transcript(
        self,
        document_title: str,
        column_headers: Sequence[str],
        sections: Sequence[TranscriptSection],
        legend_title: str,
        legend_entries: Sequence[TranscriptLegendEntry],
        page_layout: PageLayout,
        output: Path,
    ) -> None:
        """Writes ``sections`` to a file at ``output`` through this class's own built-in
        ``TranscriptExporter`` implementation, auto-detected from ``output``'s file
        extension - no prior injection call is needed; a fresh exporter instance is
        resolved for every call, purely from ``output``'s name.

        Raises:
            ValueError: If ``output``'s file extension matches none of ``ExportType``'s
                known transcript formats.
        """
        if output is None:
            raise TypeError("@ExportCoordinator.export_transcript: output must not be None")

        export_type = ExportType.from_suffix(output.name)
        if export_type is None:
            raise ValueError(
                "@ExportCoordinator.export_transcript: output file name has no recognized "
                f"transcript format extension: {output.name}"
            )

        _resolve_built_in_exporter(export_type).export(
            document_title, column_headers, sections, legend_title, legend_entries, page_layout, output
        )

    def export_archive(self, output: Path) -> None:
        """Writes an archive to ``output`` through the ``ArchiveExporter`` last passed
        to :meth:`inject_archive_exporter`.

        Raises:
            RuntimeError: If no ``ArchiveExporter`` has been injected yet.
        """
        _require_injected(self._archive_exporter, "ArchiveExporter")
        assert self._archive_exporter is not None
        self._archive_exporter.export(output)


def _require_injected(exporter: Any, type_name: str) -> None:
    """Guards every ``export_*`` method against being called before its matching
    ``inject_*`` method, failing fast with a message naming the missing exporter's type
    rather than an anonymous attribute error further down."""
    if exporter is not None:
        return
    raise RuntimeError(f"@ExportCoordinator: no {type_name} has been injected; call the matching inject method first")


def _resolve_built_in_exporter(export_type: ExportType) -> TranscriptExporter:
    """Maps ``export_type`` to a fresh instance of the matching built-in
    ``TranscriptExporter`` implementation, for ``export_transcript``."""
    if export_type is ExportType.PDF:
        return _TranscriptPDFExporter()
    if export_type is ExportType.EXCEL:
        return _TranscriptExcelExporter()
    if export_type is ExportType.CSV:
        return _TranscriptCSVExporter()
    if export_type is ExportType.XML:
        return _TranscriptXMLExporter()
    if export_type is ExportType.JSON:
        return _TranscriptJsonExporter()
    return _TranscriptDocxExporter()


def _prepare(output: Path) -> Path:
    """Creates ``output``'s parent directory if it does not exist yet, and deletes any
    file already at ``output``, so every exporter below can write to a fresh file
    regardless of what the caller passed in."""
    parent = output.absolute().parent
    parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    return output


def _validate(
    document_title: str,
    column_headers: Sequence[str],
    sections: Sequence[TranscriptSection],
    legend_entries: Sequence[TranscriptLegendEntry],
    page_layout: PageLayout,
    output: Path,
    exporter: str,
) -> None:
    """The shared argument validation every built-in exporter runs first - one place for
    the checks the Java exporters each repeat inline."""
    for name, value in (
        ("document_title", document_title),
        ("column_headers", column_headers),
        ("sections", sections),
        ("legend_entries", legend_entries),
        ("page_layout", page_layout),
        ("output", output),
    ):
        if value is None:
            raise TypeError(f"@{exporter}.export: {name} must not be None")
    if not column_headers:
        raise ValueError(f"@{exporter}.export: column_headers must not be empty")


class DirectoryZipExporter(ArchiveExporter):
    """Zips a directory's entire contents into a single archive - a full,
    format-agnostic backup of everything under some source location, as opposed to the
    other exporters in this module, which each write one already-collected data set as a
    table or transcript."""

    def __init__(self, source_directory: Path, before_export: Callable[[], None] | None = None) -> None:
        """Args:
            source_directory: The directory whose contents :meth:`export` zips.
            before_export: Run once, before the directory is walked, e.g. to flush
                pending in-memory changes to disk first so the archive reflects the
                latest state.
        """
        if source_directory is None:
            raise TypeError("@DirectoryZipExporter: source_directory must not be None")
        self._source_directory = source_directory
        self._before_export = before_export or (lambda: None)

    def export(self, output: Path) -> None:
        """Runs the before-export hook, then zips every file under the source directory,
        preserving its directory structure, into an archive at ``output``.

        Raises:
            OSError: If the source directory does not exist, or the archive cannot be
                written.
        """
        if output is None:
            raise TypeError("@DirectoryZipExporter.export: output must not be None")

        self._before_export()

        if not self._source_directory.is_dir():
            raise OSError(f"No directory found at {self._source_directory}")

        output_path = _prepare(output)

        with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(self._source_directory.rglob("*")):
                if not file.is_file():
                    continue
                entry_name = file.relative_to(self._source_directory).as_posix()
                archive.write(file, entry_name)


# Attached as a member of ExportCoordinator, mirroring the Java nested class.
ExportCoordinator.DirectoryZipExporter = DirectoryZipExporter  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------- PDF


@dataclass(frozen=True, slots=True)
class _SectionHeading:
    """A section's heading line, e.g. a semester's name, rendered as a shaded row
    spanning every column."""

    title: str


@dataclass(frozen=True, slots=True)
class _DataRow:
    """One row of a section's own data, rendered as an ordinary bordered, possibly
    banded table row."""

    cells: list[str]


@dataclass(frozen=True, slots=True)
class _Spacer:
    """A short, blank, unbordered gap between one section's last data row and the next
    section's heading, so two adjacent groups read as visibly distinct rather than the
    shaded heading row alone having to carry that signal."""


_Line = _SectionHeading | _DataRow | _Spacer


class _TranscriptPDFExporter:
    """Exports a grouped, official-transcript-style PDF via reportlab: a shaded title
    banner with "Page X of Y", then one bordered table per page - a shaded, bold column
    header row; one light-gray, bold section row per group; and bordered, banded data
    rows beneath it - matching the look of a default Microsoft Word table, with section
    grouping added on top (which a plain flat table has no way to express) - plus an
    optional closing legend page. The layout constants and pagination logic are ported
    verbatim from the Java (PDFBox) implementation; reportlab shares PDFBox's
    bottom-left origin and point unit, so the geometry carries over unchanged."""

    _MARGIN = 50.0
    _BANNER_HEIGHT = 24.0
    _TITLE_FONT_SIZE = 12.0
    _HEADER_FONT_SIZE = 9.0
    _SECTION_FONT_SIZE = 10.0
    _CELL_FONT_SIZE = 9.0
    _ROW_HEIGHT = 18.0
    _SPACER_HEIGHT = _ROW_HEIGHT * 0.6
    _SECTION_GAP = 6.0
    _FOOTER_HEIGHT = 30.0
    _CELL_PADDING_X = 4.0
    _BORDER_WIDTH = 0.75

    _FONT = "Helvetica"
    _FONT_BOLD = "Helvetica-Bold"

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptPDFExporter"
        )

        from reportlab.lib.colors import Color
        from reportlab.pdfgen import canvas as pdf_canvas

        self._banner_fill = Color(160 / 255, 160 / 255, 160 / 255)
        self._rule_color = Color(60 / 255, 60 / 255, 60 / 255)
        self._border_color = Color(140 / 255, 140 / 255, 140 / 255)
        self._header_fill = Color(230 / 255, 230 / 255, 230 / 255)
        self._band_fill = Color(247 / 255, 247 / 255, 247 / 255)
        self._black = Color(0, 0, 0)

        output_path = _prepare(output)

        page_width, page_height = _resolve_page_size(page_layout)
        content_width = page_width - self._MARGIN * 2
        column_widths = self._compute_column_widths(column_headers, sections, content_width)

        table_top = page_height - self._MARGIN - self._BANNER_HEIGHT - self._SECTION_GAP
        table_bottom = self._MARGIN + self._FOOTER_HEIGHT
        available_height = table_top - table_bottom - self._ROW_HEIGHT

        lines = _flatten(sections)
        pages = self._paginate(lines, available_height)
        has_legend = bool(legend_entries)
        total_pages = len(pages) + (1 if has_legend else 0)

        canvas = pdf_canvas.Canvas(str(output_path), pagesize=(page_width, page_height))

        for index, page_lines in enumerate(pages):
            self._render_table_page(
                canvas, page_width, page_height, document_title, column_headers, column_widths,
                page_lines, index + 1, total_pages,
            )
            canvas.showPage()

        if has_legend:
            self._render_legend_page(
                canvas, page_width, page_height, document_title, legend_title, legend_entries, total_pages, total_pages
            )
            canvas.showPage()

        canvas.save()

    # ------------------------------------------------------------- pagination

    @classmethod
    def _line_height(cls, line: _Line) -> float:
        return cls._SPACER_HEIGHT if isinstance(line, _Spacer) else cls._ROW_HEIGHT

    @classmethod
    def _paginate(cls, lines: list[_Line], available_height: float) -> list[list[_Line]]:
        """Packs ``lines`` into pages that fit within ``available_height`` each, pushing
        a section heading that would otherwise land with no room left for even one of
        its own rows onto the next page instead, so a heading is never orphaned from its
        own rows; a spacer that would otherwise start a fresh page is dropped rather
        than carried over, since the page break itself already separates the two
        sections."""
        pages: list[list[_Line]] = []
        current: list[_Line] = []
        used = 0.0

        for line in lines:
            if not current and isinstance(line, _Spacer):
                continue

            height = cls._line_height(line)
            would_orphan_heading = (
                isinstance(line, _SectionHeading) and used + height + cls._ROW_HEIGHT > available_height
            )

            if used + height > available_height or would_orphan_heading:
                pages.append(current)
                current = []
                used = 0.0

            current.append(line)
            used += height

        if current or not pages:
            pages.append(current)

        return pages

    # ---------------------------------------------------------------- metrics

    def _text_width(self, font: str, font_size: float, text: str) -> float:
        from reportlab.pdfbase.pdfmetrics import stringWidth

        return stringWidth(text, font, font_size)

    def _truncate_to_width(self, font: str, font_size: float, text: str, max_width: float) -> str:
        """Shortens ``text`` with a trailing ellipsis so it fits within ``max_width``,
        leaving it unchanged if it already fits."""
        if self._text_width(font, font_size, text) <= max_width:
            return text
        ellipsis = "..."
        truncated = text
        while truncated and self._text_width(font, font_size, truncated + ellipsis) > max_width:
            truncated = truncated[:-1]
        return truncated + ellipsis

    def _compute_column_widths(
        self, headers: Sequence[str], sections: Sequence[TranscriptSection], content_width: float
    ) -> list[float]:
        """Computes each column's width, proportional to the widest cell actually seen
        in that column (header included) across every section, scaled to fill
        ``content_width``."""
        natural = [
            self._text_width(self._FONT_BOLD, self._HEADER_FONT_SIZE, header) + self._CELL_PADDING_X * 2
            for header in headers
        ]

        for section in sections:
            for row in section.rows:
                for index in range(min(len(row), len(natural))):
                    natural[index] = max(
                        natural[index],
                        self._text_width(self._FONT, self._CELL_FONT_SIZE, row[index]) + self._CELL_PADDING_X * 2,
                    )

        total = sum(natural)
        if total <= 0:
            return [content_width / len(natural)] * len(natural)
        return [width / total * content_width for width in natural]

    # -------------------------------------------------------------- rendering

    def _render_table_page(
        self,
        canvas: Any,
        page_width: float,
        page_height: float,
        document_title: str,
        headers: Sequence[str],
        column_widths: list[float],
        lines: list[_Line],
        page_number: int,
        total_pages: int,
    ) -> None:
        content_width = page_width - self._MARGIN * 2
        y = page_height - self._MARGIN
        y = self._write_banner(canvas, y, content_width, document_title, page_number, total_pages)
        y -= self._SECTION_GAP
        y = self._write_header_row(canvas, y, headers, column_widths)

        # Banding restarts at each section heading, so every group's own data rows read
        # as their own little table rather than continuing an arbitrary odd/even pattern
        # inherited from the previous section.
        row_index = 0
        for line in lines:
            if isinstance(line, _SectionHeading):
                y = self._write_section_heading(canvas, y, line.title, column_widths)
                row_index = 0
            elif isinstance(line, _DataRow):
                y = self._write_data_row(canvas, y, line.cells, column_widths, row_index)
                row_index += 1
            else:
                y -= self._SPACER_HEIGHT

        self._write_footer(canvas, page_width)

    def _render_legend_page(
        self,
        canvas: Any,
        page_width: float,
        page_height: float,
        document_title: str,
        legend_title: str,
        legend_entries: Sequence[TranscriptLegendEntry],
        page_number: int,
        total_pages: int,
    ) -> None:
        """Renders the closing legend page: each entry's label in a left column wide
        enough for the widest one, and its description starting at that same fixed
        offset on every row - a genuinely aligned two-column layout, not embedded
        literal spacing that would drift depending on how wide each label happens to
        render."""
        content_width = page_width - self._MARGIN * 2
        y = page_height - self._MARGIN
        y = self._write_banner(canvas, y, content_width, document_title, page_number, total_pages)
        y -= self._SECTION_GAP * 3

        self._write_text(canvas, self._FONT_BOLD, self._SECTION_FONT_SIZE + 1, self._MARGIN, y, legend_title)
        y -= self._ROW_HEIGHT

        label_column_width = max(
            (self._text_width(self._FONT, self._CELL_FONT_SIZE, entry.label) for entry in legend_entries),
            default=0.0,
        )
        description_x = self._MARGIN + label_column_width + self._CELL_PADDING_X * 4

        for entry in legend_entries:
            self._write_text(canvas, self._FONT, self._CELL_FONT_SIZE, self._MARGIN, y, entry.label)
            self._write_text(canvas, self._FONT, self._CELL_FONT_SIZE, description_x, y, entry.description)
            y -= self._ROW_HEIGHT * 0.9

        self._write_footer(canvas, page_width)

    def _write_banner(
        self, canvas: Any, y: float, content_width: float, title: str, page_number: int, total_pages: int
    ) -> float:
        """Writes the shaded title banner spanning the content width, with ``title`` at
        its left and "Page X of Y" at its right; returns the banner's bottom edge."""
        banner_bottom = y - self._BANNER_HEIGHT

        canvas.setFillColor(self._banner_fill)
        canvas.rect(self._MARGIN, banner_bottom, content_width, self._BANNER_HEIGHT, stroke=0, fill=1)

        canvas.setFillColor(self._black)
        baseline = banner_bottom + (self._BANNER_HEIGHT - self._TITLE_FONT_SIZE) / 2 + self._TITLE_FONT_SIZE * 0.2
        self._write_text(
            canvas, self._FONT_BOLD, self._TITLE_FONT_SIZE, self._MARGIN + self._CELL_PADDING_X * 2, baseline, title
        )

        page_label = f"Page {page_number} of {total_pages}"
        page_label_width = self._text_width(self._FONT_BOLD, self._HEADER_FONT_SIZE, page_label)
        self._write_text(
            canvas, self._FONT_BOLD, self._HEADER_FONT_SIZE,
            self._MARGIN + content_width - page_label_width - self._CELL_PADDING_X * 2, baseline, page_label,
        )

        return banner_bottom

    def _write_header_row(self, canvas: Any, y: float, headers: Sequence[str], column_widths: list[float]) -> float:
        """Writes the shaded, bold column header row, bordered like every other row of
        the table; returns the row's bottom edge."""
        row_bottom = y - self._ROW_HEIGHT

        canvas.setFillColor(self._header_fill)
        canvas.rect(self._MARGIN, row_bottom, sum(column_widths), self._ROW_HEIGHT, stroke=0, fill=1)

        canvas.setFillColor(self._black)
        canvas.setStrokeColor(self._border_color)
        canvas.setLineWidth(self._BORDER_WIDTH)

        x = self._MARGIN
        for index, header in enumerate(headers):
            canvas.rect(x, row_bottom, column_widths[index], self._ROW_HEIGHT, stroke=1, fill=0)
            text = self._truncate_to_width(
                self._FONT_BOLD, self._HEADER_FONT_SIZE, header, column_widths[index] - self._CELL_PADDING_X * 2
            )
            self._write_text(
                canvas, self._FONT_BOLD, self._HEADER_FONT_SIZE, x + self._CELL_PADDING_X, row_bottom + 5, text
            )
            x += column_widths[index]

        return row_bottom

    def _write_section_heading(self, canvas: Any, y: float, title: str, column_widths: list[float]) -> float:
        """Writes one light-gray, bold section heading row, spanning every column as a
        single bordered cell; returns the row's bottom edge."""
        row_bottom = y - self._ROW_HEIGHT
        table_width = sum(column_widths)

        canvas.setFillColor(self._header_fill)
        canvas.rect(self._MARGIN, row_bottom, table_width, self._ROW_HEIGHT, stroke=0, fill=1)

        canvas.setFillColor(self._black)
        canvas.setStrokeColor(self._border_color)
        canvas.setLineWidth(self._BORDER_WIDTH)
        canvas.rect(self._MARGIN, row_bottom, table_width, self._ROW_HEIGHT, stroke=1, fill=0)

        self._write_text(
            canvas, self._FONT_BOLD, self._SECTION_FONT_SIZE, self._MARGIN + self._CELL_PADDING_X, row_bottom + 5, title
        )

        return row_bottom

    def _write_data_row(
        self, canvas: Any, y: float, cells: list[str], column_widths: list[float], row_index: int
    ) -> float:
        """Writes one bordered data row, its background banded on every other
        ``row_index`` within the current section; returns the row's bottom edge."""
        row_bottom = y - self._ROW_HEIGHT

        if row_index % 2 == 1:
            canvas.setFillColor(self._band_fill)
            canvas.rect(self._MARGIN, row_bottom, sum(column_widths), self._ROW_HEIGHT, stroke=0, fill=1)

        canvas.setFillColor(self._black)
        canvas.setStrokeColor(self._border_color)
        canvas.setLineWidth(self._BORDER_WIDTH)

        x = self._MARGIN
        for index in range(min(len(cells), len(column_widths))):
            canvas.rect(x, row_bottom, column_widths[index], self._ROW_HEIGHT, stroke=1, fill=0)
            text = self._truncate_to_width(
                self._FONT, self._CELL_FONT_SIZE, cells[index], column_widths[index] - self._CELL_PADDING_X * 2
            )
            self._write_text(canvas, self._FONT, self._CELL_FONT_SIZE, x + self._CELL_PADDING_X, row_bottom + 5, text)
            x += column_widths[index]

        return row_bottom

    def _write_footer(self, canvas: Any, page_width: float) -> None:
        """Writes the page footer: a thin rule followed by a "Generated on {date}"
        line."""
        rule_y = self._MARGIN + self._FOOTER_HEIGHT - 10

        canvas.setStrokeColor(self._rule_color)
        canvas.setLineWidth(0.75)
        canvas.line(self._MARGIN, rule_y, page_width - self._MARGIN, rule_y)

        generated_on = date.today().strftime("%d.%m.%Y")
        self._write_text(canvas, self._FONT, 8.0, self._MARGIN, self._MARGIN, f"Generated on {generated_on}")

    @staticmethod
    def _write_text(canvas: Any, font: str, font_size: float, x: float, y: float, text: str) -> None:
        canvas.setFont(font, font_size)
        canvas.drawString(x, y, text)


def _resolve_page_size(page_layout: PageLayout) -> tuple[float, float]:
    """Resolves ``page_layout`` to the ``(width, height)`` its page size and orientation
    describe, swapping width and height of the portrait rectangle for landscape."""
    from reportlab.lib import pagesizes

    portrait = {
        PageFormat.A3: pagesizes.A3,
        PageFormat.A4: pagesizes.A4,
        PageFormat.A5: pagesizes.A5,
    }[page_layout.format]

    if page_layout.orientation is PageOrientation.LANDSCAPE:
        return portrait[1], portrait[0]
    return portrait


def _flatten(sections: Sequence[TranscriptSection]) -> list[_Line]:
    """Flattens ``sections`` into a single ordered list of lines: one heading followed
    by that section's own data rows, per section, with a spacer between one section and
    the next (but not before the first or after the last), so pagination does not need
    to know about section boundaries."""
    lines: list[_Line] = []
    for index, section in enumerate(sections):
        if index > 0:
            lines.append(_Spacer())
        lines.append(_SectionHeading(section.title))
        lines.extend(_DataRow(row) for row in section.rows)
    return lines


# --------------------------------------------------------------------------- Excel


class _TranscriptExcelExporter:
    """Exports a grouped transcript to an Excel workbook via openpyxl: a shaded, bold
    header row, thin borders around every cell, and light banding on alternating data
    rows, plus one light-gray, bold, merged section row per group and a blank row
    between sections. An optional legend goes on its own sheet. The Excel counterpart to
    the PDF exporter, sharing the same input shape so a caller can offer both formats
    from the same already-built data."""

    _CELL_FONT_SIZE = 11
    _BORDER_COLOR = "8C8C8C"
    _HEADER_FILL = "E6E6E6"
    _BAND_FILL = "F7F7F7"

    # ECMA-376 paper size codes, the same constants POI's PrintSetup wraps.
    _PAPER_SIZES: ClassVar[dict[PageFormat, int]] = {PageFormat.A3: 8, PageFormat.A4: 9, PageFormat.A5: 11}

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptExcelExporter"
        )

        from openpyxl import Workbook

        output_path = _prepare(output)

        workbook = Workbook()
        workbook.remove(workbook.active)  # openpyxl's implicit default sheet

        self._write_transcript_sheet(workbook, document_title, column_headers, sections, page_layout)
        if legend_entries:
            self._write_legend_sheet(workbook, legend_title, legend_entries, page_layout)

        workbook.save(output_path)

    def _write_transcript_sheet(
        self,
        workbook: Any,
        title: str,
        headers: Sequence[str],
        sections: Sequence[TranscriptSection],
        page_layout: PageLayout,
    ) -> None:
        """Writes the main sheet: the bold, shaded header row, then one light-gray,
        bold, merged section row per group followed by that group's own banded data rows
        and a blank spacer row before the next group."""
        sheet = workbook.create_sheet(_sheet_name(title))
        self._apply_print_setup(sheet, page_layout)

        header_style = self._row_style(bold=True, fill=self._HEADER_FILL)
        section_style = self._row_style(bold=True, fill=self._HEADER_FILL)
        cell_style = self._row_style(bold=False, fill=None)
        banded_style = self._row_style(bold=False, fill=self._BAND_FILL)

        widths = [len(header) for header in headers]

        row_index = 1  # openpyxl rows are 1-based
        self._write_row(sheet, row_index, headers, header_style)
        row_index += 1

        for section in sections:
            for column in range(1, len(headers) + 1):
                self._style_cell(sheet.cell(row=row_index, column=column), section_style)
            sheet.cell(row=row_index, column=1).value = section.title
            if len(headers) > 1:
                sheet.merge_cells(start_row=row_index, start_column=1, end_row=row_index, end_column=len(headers))
            row_index += 1

            data_index = 0
            for row in section.rows:
                self._write_row(sheet, row_index, row, banded_style if data_index % 2 == 1 else cell_style)
                for cell_index, value in enumerate(row[: len(widths)]):
                    widths[cell_index] = max(widths[cell_index], len(value))
                data_index += 1
                row_index += 1

            row_index += 1  # blank row separating this section from the next

        # openpyxl has no auto-size; approximating POI's autoSizeColumn from the widest
        # string actually written per column.
        from openpyxl.utils import get_column_letter

        for index, width in enumerate(widths, start=1):
            sheet.column_dimensions[get_column_letter(index)].width = width + 3

        sheet.freeze_panes = "A2"

    def _apply_print_setup(self, sheet: Any, page_layout: PageLayout) -> None:
        """Applies the page size and orientation to the sheet's print setup, the Excel
        counterpart of the page rectangle the PDF exporter renders each page at."""
        sheet.page_setup.paperSize = self._PAPER_SIZES[page_layout.format]
        sheet.page_setup.orientation = (
            "landscape" if page_layout.orientation is PageOrientation.LANDSCAPE else "portrait"
        )

    def _write_legend_sheet(
        self, workbook: Any, title: str, entries: Sequence[TranscriptLegendEntry], page_layout: PageLayout
    ) -> None:
        """Writes the legend sheet: labels in a bold left column, descriptions in a
        plain right column, both auto-sized."""
        from openpyxl.styles import Font

        sheet = workbook.create_sheet(_sheet_name(title))
        self._apply_print_setup(sheet, page_layout)

        bold = Font(bold=True)
        for index, entry in enumerate(entries, start=1):
            label_cell = sheet.cell(row=index, column=1)
            label_cell.value = entry.label
            label_cell.font = bold
            sheet.cell(row=index, column=2).value = entry.description

        sheet.column_dimensions["A"].width = max((len(entry.label) for entry in entries), default=8) + 3
        sheet.column_dimensions["B"].width = max((len(entry.description) for entry in entries), default=8) + 3

    def _write_row(self, sheet: Any, row_index: int, values: Sequence[str], style: dict[str, Any]) -> None:
        for column, value in enumerate(values, start=1):
            cell = sheet.cell(row=row_index, column=column)
            cell.value = value
            self._style_cell(cell, style)

    @staticmethod
    def _style_cell(cell: Any, style: dict[str, Any]) -> None:
        cell.font = style["font"]
        cell.border = style["border"]
        cell.alignment = style["alignment"]
        if style["fill"] is not None:
            cell.fill = style["fill"]

    def _row_style(self, bold: bool, fill: str | None) -> dict[str, Any]:
        """Builds a bordered, vertically-centered style bundle for a header, section or
        data row, with an optional background fill - openpyxl styles are assigned per
        cell, so the bundle is applied by :meth:`_style_cell`."""
        from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

        side = Side(style="thin", color=self._BORDER_COLOR)
        return {
            "font": Font(bold=bold, size=self._CELL_FONT_SIZE),
            "border": Border(top=side, bottom=side, left=side, right=side),
            "alignment": Alignment(vertical="center"),
            "fill": PatternFill(fill_type="solid", start_color=fill, end_color=fill) if fill else None,
        }


def _sheet_name(title: str) -> str:
    """Sanitizes ``title`` into a valid Excel sheet name: strips the characters Excel
    forbids in one (``\\ / ? * [ ] :``), and truncates to its 31-character limit."""
    sanitized = "".join(" " if character in "\\/?*[]:" else character for character in title).strip()
    truncated = sanitized[:31].strip() if len(sanitized) > 31 else sanitized
    return truncated or "Sheet1"


# ----------------------------------------------------------------------------- CSV


class _TranscriptCSVExporter:
    """Exports a grouped transcript to a CSV file: a single-cell title row, a blank
    separator row, the column header row, then one single-cell section-title row per
    group followed by that group's own data rows and a blank row before the next group -
    the CSV counterpart of the PDF/Excel section grouping, expressed with plain rows
    since CSV has no concept of shading, borders or merged cells. An optional closing
    legend follows, its own single-cell title row followed by one ``label,description``
    row per entry. Fields are quoted per RFC 4180 whenever they contain a comma, a
    double quote, or a line break. ``page_layout`` is accepted only to satisfy
    ``TranscriptExporter`` and has no effect, since CSV has no notion of pages."""

    _LINE_SEPARATOR = "\r\n"
    """The record separator written after every row, per RFC 4180."""

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptCSVExporter"
        )

        output_path = _prepare(output)

        buffer = io.StringIO()
        writer = _csv.writer(buffer, quoting=_csv.QUOTE_MINIMAL, lineterminator=self._LINE_SEPARATOR)

        writer.writerow([document_title])
        buffer.write(self._LINE_SEPARATOR)
        writer.writerow(column_headers)

        for section in sections:
            writer.writerow([section.title])
            for row in section.rows:
                writer.writerow(row)
            buffer.write(self._LINE_SEPARATOR)

        if legend_entries:
            writer.writerow([legend_title])
            for entry in legend_entries:
                writer.writerow([entry.label, entry.description])

        output_path.write_text(buffer.getvalue(), encoding="utf-8", newline="")


# ----------------------------------------------------------------------------- XML


class _TranscriptXMLExporter:
    """Exports a grouped transcript to an XML file via the stdlib's ElementTree: a root
    ``<transcript>`` element carrying the title as its ``title`` attribute, a
    ``<columns>`` element listing the headers, a ``<sections>`` element with one
    ``<section title="...">`` per group containing that group's own ``<row>`` elements
    of ``<cell>`` values, and - if the legend is not empty - a closing
    ``<legend title="...">`` element with one ``<entry label="..." description="..."/>``
    per entry. ``page_layout`` is accepted only to satisfy ``TranscriptExporter`` and
    has no effect, since XML has no notion of pages."""

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptXMLExporter"
        )

        output_path = _prepare(output)

        root = ET.Element("transcript", attrib={"title": document_title})

        columns_element = ET.SubElement(root, "columns")
        for header in column_headers:
            ET.SubElement(columns_element, "column").text = header

        sections_element = ET.SubElement(root, "sections")
        for section in sections:
            section_element = ET.SubElement(sections_element, "section", attrib={"title": section.title})
            for row in section.rows:
                row_element = ET.SubElement(section_element, "row")
                for cell in row:
                    ET.SubElement(row_element, "cell").text = cell

        if legend_entries:
            legend_element = ET.SubElement(root, "legend", attrib={"title": legend_title})
            for entry in legend_entries:
                ET.SubElement(legend_element, "entry", attrib={"label": entry.label, "description": entry.description})

        tree = ET.ElementTree(root)
        ET.indent(tree, space="  ")
        tree.write(output_path, encoding="utf-8", xml_declaration=True)


# ---------------------------------------------------------------------------- JSON


class _TranscriptJsonExporter:
    """Exports a grouped transcript to a JSON file: a root object with the title under
    ``"title"``, the headers under ``"columns"``, and the sections under ``"sections"``
    as an array of ``{"title": ..., "rows": [[...], ...]}`` objects, one per group. If
    the legend is not empty, a closing ``"legend"`` object is added with its title and
    an ``"entries"`` array of ``{"label": ..., "description": ...}`` objects.
    ``page_layout`` is accepted only to satisfy ``TranscriptExporter`` and has no
    effect, since JSON has no notion of pages."""

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptJsonExporter"
        )

        output_path = _prepare(output)

        root: dict[str, Any] = {
            "title": document_title,
            "columns": list(column_headers),
            "sections": [{"title": section.title, "rows": [list(row) for row in section.rows]} for section in sections],
        }

        if legend_entries:
            root["legend"] = {
                "title": legend_title,
                "entries": [{"label": entry.label, "description": entry.description} for entry in legend_entries],
            }

        output_path.write_text(_json.dumps(root, indent=2, ensure_ascii=False), encoding="utf-8")


# ---------------------------------------------------------------------------- DOCX


class _TranscriptDocxExporter:
    """Exports a grouped transcript to a Word document (.docx) via python-docx: a bold
    title paragraph, then a single bordered table spanning the page width - a shaded,
    bold header row, one shaded, bold section-title row per group (the row's remaining
    cells left blank rather than merged, matching the Java exporter's deliberate
    avoidance of the brittle cell-merge API) followed by that group's own data rows,
    banded on alternating rows - plus an optional closing legend: its own title
    paragraph followed by a borderless ``label | description`` table, the label bold.
    The page size and orientation are applied to the document's one section. The Word
    counterpart of the PDF and Excel exporters, sharing the same input shape."""

    _HEADER_FILL = "E6E6E6"
    _BAND_FILL = "F7F7F7"
    _BORDER_COLOR = "8C8C8C"
    _BORDER_SIZE = 4
    """Border weight, in eighths of a point, of every table border."""

    _TITLE_FONT_SIZE = 16
    _LEGEND_TITLE_FONT_SIZE = 13
    _CELL_FONT_SIZE = 10

    # A3/A4/A5 in twips (1/1440 inch), portrait: (width, height) - the same constants
    # the Java exporter feeds into CTPageSz.
    _PAGE_SIZES: ClassVar[dict[PageFormat, tuple[int, int]]] = {
        PageFormat.A3: (16838, 23811),
        PageFormat.A4: (11906, 16838),
        PageFormat.A5: (8391, 11906),
    }

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
        _validate(
            document_title, column_headers, sections, legend_entries, page_layout, output, "TranscriptDocxExporter"
        )

        from docx import Document

        output_path = _prepare(output)

        document = Document()
        self._apply_page_layout(document, page_layout)
        self._write_title(document, document_title)
        self._write_transcript_table(document, column_headers, sections)

        if legend_entries:
            self._write_legend(document, legend_title, legend_entries)

        document.save(str(output_path))

    def _apply_page_layout(self, document: Any, page_layout: PageLayout) -> None:
        """Applies the page size and orientation to the document's one and only
        section, the Word counterpart of the page rectangle the PDF exporter renders
        each page at."""
        from docx.enum.section import WD_ORIENT
        from docx.shared import Twips

        section = document.sections[0]
        portrait_width, portrait_height = self._PAGE_SIZES[page_layout.format]
        landscape = page_layout.orientation is PageOrientation.LANDSCAPE

        section.page_width = Twips(portrait_height if landscape else portrait_width)
        section.page_height = Twips(portrait_width if landscape else portrait_height)
        section.orientation = WD_ORIENT.LANDSCAPE if landscape else WD_ORIENT.PORTRAIT

    def _write_title(self, document: Any, title: str) -> None:
        from docx.shared import Pt

        run = document.add_paragraph().add_run(title)
        run.bold = True
        run.font.size = Pt(self._TITLE_FONT_SIZE)

    def _write_transcript_table(
        self, document: Any, headers: Sequence[str], sections: Sequence[TranscriptSection]
    ) -> None:
        """Writes the main table: the shaded, bold header row, then one shaded, bold
        section-title row per group followed by that group's own banded data rows."""
        table = document.add_table(rows=1, cols=len(headers))
        table.autofit = True
        self._apply_borders(table)

        self._write_row(table.rows[0], headers, bold=True, fill_hex=self._HEADER_FILL)

        for section in sections:
            self._write_row(table.add_row(), [section.title], bold=True, fill_hex=self._HEADER_FILL)

            data_index = 0
            for row in section.rows:
                self._write_row(
                    table.add_row(), row, bold=False,
                    fill_hex=self._BAND_FILL if data_index % 2 == 1 else None,
                )
                data_index += 1

    def _write_legend(self, document: Any, legend_title: str, legend_entries: Sequence[TranscriptLegendEntry]) -> None:
        """Writes the closing legend: its own title paragraph, then a borderless,
        unshaded table with the label in a bold left column and the description in a
        plain right column."""
        from docx.shared import Pt

        document.add_paragraph()

        title_run = document.add_paragraph().add_run(legend_title)
        title_run.bold = True
        title_run.font.size = Pt(self._LEGEND_TITLE_FONT_SIZE)

        table = document.add_table(rows=1, cols=2)  # python-docx tables default borderless

        for index, entry in enumerate(legend_entries):
            row = table.rows[0] if index == 0 else table.add_row()
            self._write_cell(row.cells[0], entry.label, bold=True, fill_hex=None)
            self._write_cell(row.cells[1], entry.description, bold=False, fill_hex=None)

    def _apply_borders(self, table: Any) -> None:
        """Applies bordered edges (outer and inside) to the table - python-docx has no
        border API of its own, so the ``w:tblBorders`` element is written directly, the
        same XML POI's border setters produce."""
        from docx.oxml.ns import qn
        from docx.oxml.parser import OxmlElement

        table_properties = table._tbl.tblPr
        borders = OxmlElement("w:tblBorders")
        for edge in ("top", "bottom", "left", "right", "insideH", "insideV"):
            border = OxmlElement(f"w:{edge}")
            border.set(qn("w:val"), "single")
            border.set(qn("w:sz"), str(self._BORDER_SIZE))
            border.set(qn("w:space"), "0")
            border.set(qn("w:color"), self._BORDER_COLOR)
            borders.append(border)
        table_properties.append(borders)

    def _write_row(self, row: Any, values: Sequence[str], bold: bool, fill_hex: str | None) -> None:
        """Writes one table row: ``values`` into the row's cells in order, blank for any
        cell beyond ``values``' length (e.g. a section-title row's cells after the
        first), optionally bold and/or shaded."""
        for index, cell in enumerate(row.cells):
            self._write_cell(cell, values[index] if index < len(values) else "", bold, fill_hex)

    def _write_cell(self, cell: Any, text: str, bold: bool, fill_hex: str | None) -> None:
        from docx.oxml.ns import qn
        from docx.oxml.parser import OxmlElement
        from docx.shared import Pt

        if fill_hex is not None:
            shading = OxmlElement("w:shd")
            shading.set(qn("w:val"), "clear")
            shading.set(qn("w:fill"), fill_hex)
            cell._tc.get_or_add_tcPr().append(shading)

        run = cell.paragraphs[0].add_run(text)
        run.bold = bold
        run.font.size = Pt(self._CELL_FONT_SIZE)
