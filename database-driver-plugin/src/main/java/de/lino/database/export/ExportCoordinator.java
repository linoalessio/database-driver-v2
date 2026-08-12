package de.lino.database.export;

import de.lino.database.export.archiv.ArchiveExporter;
import de.lino.database.export.data.DataExporter;
import de.lino.database.export.transcript.TranscriptExporter;
import de.lino.database.export.transcript.TranscriptLegendEntry;
import de.lino.database.export.transcript.TranscriptSection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A single, generic access point for every exporter kind this module ships -
 * flat tables ({@link DataExporter}), grouped transcripts ({@link TranscriptExporter})
 * and whole-directory archives ({@link ArchiveExporter}) - wired together purely
 * through interfaces rather than any one exporter implementation.
 *
 * <p>{@link #exportTable}, {@link #exportTranscript} and {@link #exportArchive} never
 * call a concrete exporter directly; each runs through whichever {@link DataExporter},
 * {@link TranscriptExporter} or {@link ArchiveExporter} was last handed to this class
 * through {@link ExporterInjector}'s setter methods - <b>interface injection</b>. The
 * three nested classes below ({@link TranscriptPDFExporter},
 * {@link TranscriptExcelExporter} and {@link DirectoryZipExporter}) are this module's
 * own default implementations of the {@link TranscriptExporter} and
 * {@link ArchiveExporter} interfaces, bundled here as members rather than kept as
 * separate top-level classes; a caller is free to inject any other implementation
 * instead - of its own, or from another project entirely - since the coordination
 * logic itself (the four methods above) depends on nothing but the three interfaces.
 * No default {@link DataExporter} ships with this module; a caller that needs one
 * supplies its own. None of the three exporter interfaces, {@link ExporterInjector},
 * nor this class's own inject/export methods mention any application-specific type,
 * so the whole mechanism is safe to reuse unchanged by any application depending on
 * this module, each pointing it at its own exporter implementations and its own
 * output paths.
 *
 * <p>Example wiring, using every default implementation this class ships:
 * <pre>{@code
 * final ExportCoordinator coordinator = new ExportCoordinator();
 *
 * coordinator.injectTranscriptExporter(new ExportCoordinator.TranscriptPDFExporter()); // or new TranscriptExcelExporter()
 * coordinator.injectArchiveExporter(new ExportCoordinator.DirectoryZipExporter(Path.of("/var/data/app")));
 *
 * coordinator.exportTranscript("Transcript", headers, sections, "Grading Scale", legend, Path.of("transcript.xlsx"));
 * coordinator.exportArchive(Path.of("backup.zip"));
 * }</pre>
 *
 * <p>Not thread-safe: swapping an exporter with another {@code inject} call while a
 * previously injected one is mid-export is not synchronized against.
 */
public final class ExportCoordinator implements ExporterInjector {

    /**
     * The currently injected flat-table exporter, or {@code null} until
     * {@link #injectDataExporter} is called.
     */
    private DataExporter dataExporter;

    /**
     * The currently injected grouped-transcript exporter, or {@code null} until
     * {@link #injectTranscriptExporter} is called.
     */
    private TranscriptExporter transcriptExporter;

    /**
     * The currently injected archive exporter, or {@code null} until
     * {@link #injectArchiveExporter} is called.
     */
    private ArchiveExporter archiveExporter;

    /**
     * {@inheritDoc}
     */
    @Override
    public void injectDataExporter(final DataExporter dataExporter) {
        this.dataExporter = Objects.requireNonNull(dataExporter, "@ExportCoordinator.injectDataExporter: dataExporter must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void injectTranscriptExporter(final TranscriptExporter transcriptExporter) {
        this.transcriptExporter = Objects.requireNonNull(transcriptExporter, "@ExportCoordinator.injectTranscriptExporter: transcriptExporter must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void injectArchiveExporter(final ArchiveExporter archiveExporter) {
        this.archiveExporter = Objects.requireNonNull(archiveExporter, "@ExportCoordinator.injectArchiveExporter: archiveExporter must not be null");
    }

    /**
     * Writes {@code rows} to a file at {@code output} through the {@link DataExporter}
     * last passed to {@link #injectDataExporter}, with one column per entry in
     * {@code headers} and one table row per element of {@code rows}.
     *
     * @param <T> the type of the exported rows
     * @param rows the data set to export, in the order it should appear
     * @param headers the column headers, in display order
     * @param rowMapper turns a row into one cell value per header, in the same order as {@code headers}
     * @param title the document title
     * @param output the file path the export is written to; overwritten if it already exists
     * @throws IOException if the export cannot be written to {@code output}
     * @throws IllegalStateException if no {@link DataExporter} has been injected yet
     */
    public <T> void exportTable(
            final List<T> rows, final List<String> headers, final Function<T, List<String>> rowMapper, final String title, final Path output
    ) throws IOException {

        requireInjected(dataExporter, DataExporter.class);

        dataExporter.export(rows, headers, rowMapper, title, output);

    }

    /**
     * Writes {@code sections} to a file at {@code output} through the
     * {@link TranscriptExporter} last passed to {@link #injectTranscriptExporter}, one
     * column per entry in {@code columnHeaders}, with an optional closing legend.
     *
     * @param documentTitle the document title shown on every page/sheet
     * @param columnHeaders the column headers, in display order
     * @param sections the grouped rows to write, in the order they should appear
     * @param legendTitle the closing legend's heading; ignored if {@code legendEntries} is empty
     * @param legendEntries the closing legend's entries, or empty to omit it
     * @param output the file path the export is written to; overwritten if it already exists
     * @throws IOException if the export cannot be written to {@code output}
     * @throws IllegalStateException if no {@link TranscriptExporter} has been injected yet
     */
    public void exportTranscript(
            final String documentTitle, final List<String> columnHeaders, final List<TranscriptSection> sections,
            final String legendTitle, final List<TranscriptLegendEntry> legendEntries, final Path output
    ) throws IOException {

        requireInjected(transcriptExporter, TranscriptExporter.class);

        transcriptExporter.export(documentTitle, columnHeaders, sections, legendTitle, legendEntries, output);

    }

    /**
     * Writes an archive to {@code output} through the {@link ArchiveExporter} last
     * passed to {@link #injectArchiveExporter}.
     *
     * @param output the file path the archive is written to; overwritten if it already exists
     * @throws IOException if the archive cannot be written to {@code output}
     * @throws IllegalStateException if no {@link ArchiveExporter} has been injected yet
     */
    public void exportArchive(final Path output) throws IOException {

        requireInjected(archiveExporter, ArchiveExporter.class);

        archiveExporter.export(output);

    }

    /**
     * Guards every {@code exportXxx} method against being called before its matching
     * {@code injectXxx} method, failing fast with a message naming the missing
     * exporter's type rather than a bare {@link NullPointerException} further down.
     *
     * @param exporter the exporter field to check
     * @param type the exporter interface it was declared as, for the error message
     * @throws IllegalStateException if {@code exporter} is {@code null}
     */
    private static void requireInjected(final Object exporter, final Class<?> type) {
        if (exporter == null) {
            throw new IllegalStateException("@ExportCoordinator: no " + type.getSimpleName() + " has been injected; call the matching inject method first");
        }
    }

    /**
     * Creates {@code output}'s parent directory if it does not exist yet, and deletes
     * any file already at {@code output}, so every {@code export} method below can
     * write to a fresh file regardless of what the caller passed in.
     *
     * @param output the file path about to be written to
     * @return {@code output}, unchanged, for chaining at the call site
     * @throws IOException if the parent directory cannot be created or the existing file cannot be deleted
     */
    private static Path prepare(final Path output) throws IOException {

        final Path parent = output.toAbsolutePath().getParent();

        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(output)) Files.delete(output);

        return output;

    }

    /**
     * Exports a grouped, official-transcript-style PDF via Apache PDFBox: a shaded title
     * banner with "Page X of Y", then one bordered table per page - a shaded, bold column
     * header row; one light-gray, bold section row per group (e.g. a semester's name); and
     * bordered, banded data rows beneath it - matching the look of a default Microsoft Word
     * table, with section grouping added on top (which a plain flat table has no way to
     * express) - plus an optional closing legend page. See {@link TranscriptExcelExporter}
     * for the same shape as an Excel workbook instead.
     *
     * <p>Takes already-grouped, already-formatted rows via {@link TranscriptSection}, and
     * an optional legend (e.g. a grading-scale key) for the closing page. This keeps the
     * class independent of any specific application entity.
     */
    public static final class TranscriptPDFExporter implements TranscriptExporter {

        /**
         * Page margin, on all four sides, in PDF points.
         */
        private static final float MARGIN = 50f;

        /**
         * Height, in points, of the shaded title banner at the top of every page.
         */
        private static final float BANNER_HEIGHT = 24f;

        /**
         * Font size, in points, of the banner's title text.
         */
        private static final float TITLE_FONT_SIZE = 12f;

        /**
         * Font size, in points, of the column header row and the banner's page-number label.
         */
        private static final float HEADER_FONT_SIZE = 9f;

        /**
         * Font size, in points, of section heading rows and the legend page's own heading.
         */
        private static final float SECTION_FONT_SIZE = 10f;

        /**
         * Font size, in points, of data cells and legend entries.
         */
        private static final float CELL_FONT_SIZE = 9f;

        /**
         * Height, in points, of every table row (header, section, and data rows alike).
         */
        private static final float ROW_HEIGHT = 18f;

        /**
         * Height, in points, of a {@link Spacer} between two adjacent sections.
         */
        private static final float SPACER_HEIGHT = ROW_HEIGHT * 0.6f;

        /**
         * Vertical gap, in points, between the banner and the row beneath it.
         */
        private static final float SECTION_GAP = 6f;

        /**
         * Height, in points, reserved at the bottom of every page for {@link #writeFooter(PDPageContentStream, PDFont, PDPage)}.
         */
        private static final float FOOTER_HEIGHT = 30f;

        /**
         * Horizontal padding, in points, between a cell's border and its text.
         */
        private static final float CELL_PADDING_X = 4f;

        /**
         * Width, in points, of every cell and section border.
         */
        private static final float BORDER_WIDTH = 0.75f;

        /**
         * Background color of the title banner.
         */
        private static final Color BANNER_FILL = new Color(160, 160, 160);

        /**
         * Color of the footer's horizontal rule.
         */
        private static final Color RULE_COLOR = new Color(60, 60, 60);

        /**
         * Color of every cell and section border.
         */
        private static final Color BORDER_COLOR = new Color(140, 140, 140);

        /**
         * Background color of the column header row and every section heading row.
         */
        private static final Color HEADER_FILL = new Color(230, 230, 230);

        /**
         * Background color of alternating ("banded") data rows.
         */
        private static final Color BAND_FILL = new Color(247, 247, 247);

        /**
         * Date format used for the footer's "Generated on" line.
         */
        private static final DateTimeFormatter FOOTER_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

        /**
         * Writes {@code sections} to a PDF at {@code output}, one column per entry in
         * {@code columnHeaders}. Column widths auto-size to the widest cell actually seen
         * in each column. A new page is started automatically, repeating the title banner
         * and column headers, once the current page runs out of room; {@code legendEntries}
         * (if not empty) is appended as a final page under {@code legendTitle}, its two
         * columns aligned to the widest {@link TranscriptLegendEntry#label()}.
         *
         * @param documentTitle the document title shown in the banner of every page
         * @param columnHeaders the column headers, in display order
         * @param sections the grouped rows to write, in the order they should appear
         * @param legendTitle the closing legend page's heading; ignored if {@code legendEntries} is empty
         * @param legendEntries the closing legend page's entries, or empty to omit it
         * @param output the file path the PDF is written to; overwritten if it already exists
         * @throws IOException if the PDF cannot be written to {@code output}
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code columnHeaders} is empty
         */
        @Override
        public void export(
                final String documentTitle,
                final List<String> columnHeaders,
                final List<TranscriptSection> sections,
                final String legendTitle,
                final List<TranscriptLegendEntry> legendEntries,
                final Path output
        ) throws IOException {

            Objects.requireNonNull(documentTitle, "@TranscriptPDFExporter.export: documentTitle must not be null");
            Objects.requireNonNull(columnHeaders, "@TranscriptPDFExporter.export: columnHeaders must not be null");
            Objects.requireNonNull(sections, "@TranscriptPDFExporter.export: sections must not be null");
            Objects.requireNonNull(legendEntries, "@TranscriptPDFExporter.export: legendEntries must not be null");
            Objects.requireNonNull(output, "@TranscriptPDFExporter.export: output must not be null");

            if (columnHeaders.isEmpty()) throw new IllegalArgumentException("@TranscriptPDFExporter.export: columnHeaders must not be empty");

            final Path outputPath = prepare(output);

            final PDFont titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            final PDFont headerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            final PDFont cellFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final PDFont sectionFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            final float contentWidth = PDRectangle.A4.getWidth() - MARGIN * 2;
            final float[] columnWidths = computeColumnWidths(columnHeaders, sections, headerFont, cellFont, contentWidth);

            final float tableTop = PDRectangle.A4.getHeight() - MARGIN - BANNER_HEIGHT - SECTION_GAP;
            final float tableBottom = MARGIN + FOOTER_HEIGHT;
            final float availableHeight = tableTop - tableBottom - ROW_HEIGHT;

            final List<Line> lines = flatten(sections);
            final List<List<Line>> pages = paginate(lines, availableHeight);
            final boolean hasLegend = !legendEntries.isEmpty();
            final int totalPages = pages.size() + (hasLegend ? 1 : 0);

            try (PDDocument document = new PDDocument()) {

                for (int i = 0; i < pages.size(); i++) {
                    renderTablePage(document, documentTitle, columnHeaders, columnWidths, pages.get(i),
                            i + 1, totalPages, titleFont, headerFont, cellFont, sectionFont);
                }

                if (hasLegend) {
                    renderLegendPage(document, documentTitle, legendTitle, legendEntries, totalPages, totalPages, titleFont, cellFont);
                }

                document.save(outputPath.toFile());

            }

        }

        /**
         * One printable line of the table body, as flattened by
         * {@link #flatten(List)} and consumed page by page in
         * {@link #renderTablePage(PDDocument, String, List, float[], List, int, int, PDFont, PDFont, PDFont, PDFont)}.
         */
        private sealed interface Line permits SectionHeading, DataRow, Spacer {
        }

        /**
         * A section's heading line, e.g. a semester's name, rendered as a shaded row
         * spanning every column.
         *
         * @param title the heading text
         */
        private record SectionHeading(String title) implements Line {
        }

        /**
         * One row of a section's own data, rendered as an ordinary bordered, possibly
         * banded table row.
         *
         * @param cells the row's cell values, in column order
         */
        private record DataRow(List<String> cells) implements Line {
        }

        /**
         * A short, blank, unbordered gap between one section's last data row and the next
         * section's heading, so two adjacent semesters read as visibly distinct groups
         * rather than the shaded heading row alone having to carry that signal.
         */
        private record Spacer() implements Line {
        }

        /**
         * Flattens {@code sections} into a single ordered list of lines: one
         * {@link SectionHeading} followed by that section's own {@link DataRow}s, per
         * section, with a {@link Spacer} between one section and the next (but not before
         * the first or after the last), so pagination does not need to know about section
         * boundaries.
         *
         * @param sections the sections to flatten
         * @return the flattened lines, in order
         */
        private static List<Line> flatten(final List<TranscriptSection> sections) {

            final List<Line> lines = new ArrayList<>();

            for (int i = 0; i < sections.size(); i++) {

                if (i > 0) lines.add(new Spacer());

                final TranscriptSection section = sections.get(i);
                lines.add(new SectionHeading(section.title()));
                section.rows().forEach(row -> lines.add(new DataRow(row)));

            }

            return lines;

        }

        /**
         * This line's height when rendered: {@link #SPACER_HEIGHT} for a {@link Spacer},
         * {@link #ROW_HEIGHT} for anything else.
         *
         * @param line the line to measure
         * @return the line's height
         */
        private static float lineHeight(final Line line) {
            return line instanceof Spacer ? SPACER_HEIGHT : ROW_HEIGHT;
        }

        /**
         * Packs {@code lines} into pages that fit within {@code availableHeight} each,
         * pushing a {@link SectionHeading} that would otherwise land with no room left for
         * even one of its own rows onto the next page instead, so a section heading is
         * never orphaned from its own rows; a {@link Spacer} that would otherwise start a
         * fresh page is dropped rather than carried over, since the page break itself
         * already separates the two sections.
         *
         * @param lines the flattened lines to paginate
         * @param availableHeight the total height available for lines on one page
         * @return the paginated lines, one list per page
         */
        private static List<List<Line>> paginate(final List<Line> lines, final float availableHeight) {

            final List<List<Line>> pages = new ArrayList<>();
            List<Line> current = new ArrayList<>();
            float used = 0f;

            for (final Line line : lines) {

                if (current.isEmpty() && line instanceof Spacer) continue;

                final float height = lineHeight(line);
                final boolean wouldOrphanHeading = line instanceof SectionHeading && used + height + ROW_HEIGHT > availableHeight;

                if (used + height > availableHeight || wouldOrphanHeading) {
                    pages.add(current);
                    current = new ArrayList<>();
                    used = 0f;
                }

                current.add(line);
                used += height;

            }

            if (!current.isEmpty() || pages.isEmpty()) pages.add(current);

            return pages;

        }

        /**
         * Computes each column's width, proportional to the widest cell actually seen in
         * that column (header included) across every section, scaled to fill {@code contentWidth}.
         *
         * @param headers the column headers
         * @param sections the sections to measure cell widths across
         * @param headerFont the font the header row is rendered with
         * @param cellFont the font data rows are rendered with
         * @param contentWidth the total width the columns must sum to
         * @return each column's width, in the same order as {@code headers}
         * @throws IOException if a font's glyph widths cannot be read
         */
        private static float[] computeColumnWidths(
                final List<String> headers, final List<TranscriptSection> sections,
                final PDFont headerFont, final PDFont cellFont, final float contentWidth
        ) throws IOException {

            final float[] natural = new float[headers.size()];

            for (int i = 0; i < headers.size(); i++) {
                natural[i] = textWidth(headerFont, HEADER_FONT_SIZE, headers.get(i)) + CELL_PADDING_X * 2;
            }

            for (final TranscriptSection section : sections) {
                for (final List<String> row : section.rows()) {
                    for (int i = 0; i < row.size() && i < natural.length; i++) {
                        natural[i] = Math.max(natural[i], textWidth(cellFont, CELL_FONT_SIZE, row.get(i)) + CELL_PADDING_X * 2);
                    }
                }
            }

            final float sum = sum(natural);
            final float[] widths = new float[natural.length];

            for (int i = 0; i < natural.length; i++) {
                widths[i] = sum <= 0 ? contentWidth / natural.length : natural[i] / sum * contentWidth;
            }

            return widths;

        }

        /**
         * Renders one page of the table: the title banner, the column header row, then as
         * many of {@code lines} as were assigned to this page, and the page footer.
         *
         * @param document the document to append the page to
         * @param documentTitle the banner's title text
         * @param headers the column headers
         * @param columnWidths each column's width, in the same order as {@code headers}
         * @param lines this page's lines, already paginated
         * @param pageNumber this page's 1-based page number
         * @param totalPages the document's total page count
         * @param titleFont the font the banner title is rendered with
         * @param headerFont the font the column header row is rendered with
         * @param cellFont the font data rows are rendered with
         * @param sectionFont the font section headings are rendered with
         * @throws IOException if the page cannot be written to {@code document}
         */
        private static void renderTablePage(
                final PDDocument document, final String documentTitle, final List<String> headers, final float[] columnWidths,
                final List<Line> lines, final int pageNumber, final int totalPages,
                final PDFont titleFont, final PDFont headerFont, final PDFont cellFont, final PDFont sectionFont
        ) throws IOException {

            final PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {

                float y = page.getMediaBox().getHeight() - MARGIN;
                y = writeBanner(stream, titleFont, y, documentTitle, pageNumber, totalPages);
                y -= SECTION_GAP;
                y = writeHeaderRow(stream, headerFont, y, headers, columnWidths);

                // Banding restarts at each section heading, so every group's own data rows
                // read as their own little table rather than continuing an arbitrary
                // odd/even pattern inherited from the previous section.
                int rowIndex = 0;

                for (final Line line : lines) {
                    switch (line) {
                        case SectionHeading heading -> {
                            y = writeSectionHeading(stream, sectionFont, y, heading.title(), columnWidths);
                            rowIndex = 0;
                        }
                        case DataRow row -> {
                            y = writeDataRow(stream, cellFont, y, row.cells(), columnWidths, rowIndex);
                            rowIndex++;
                        }
                        case Spacer spacer -> y -= SPACER_HEIGHT;
                    }
                }

                writeFooter(stream, cellFont, page);

            }

        }

        /**
         * Renders the closing legend page: the title banner, {@code legendTitle}, then one
         * row per entry of {@code legendEntries}, each entry's {@link TranscriptLegendEntry#label()}
         * in a left column wide enough for the widest one, and its
         * {@link TranscriptLegendEntry#description()} starting at that same fixed offset on every
         * row - a genuinely aligned two-column layout, not just embedded literal spacing
         * that would drift depending on how wide each label happens to render.
         *
         * @param document the document to append the page to
         * @param documentTitle the banner's title text
         * @param legendTitle the legend section's own heading
         * @param legendEntries the legend's entries, in order
         * @param pageNumber this page's 1-based page number
         * @param totalPages the document's total page count
         * @param titleFont the font the banner title and legend heading are rendered with
         * @param textFont the font the legend entries and footer are rendered with
         * @throws IOException if the page cannot be written to {@code document}
         */
        private static void renderLegendPage(
                final PDDocument document, final String documentTitle, final String legendTitle, final List<TranscriptLegendEntry> legendEntries,
                final int pageNumber, final int totalPages, final PDFont titleFont, final PDFont textFont
        ) throws IOException {

            final PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {

                float y = page.getMediaBox().getHeight() - MARGIN;
                y = writeBanner(stream, titleFont, y, documentTitle, pageNumber, totalPages);
                y -= SECTION_GAP * 3;

                writeText(stream, titleFont, SECTION_FONT_SIZE + 1, MARGIN, y, legendTitle);
                y -= ROW_HEIGHT;

                float labelColumnWidth = 0f;
                for (final TranscriptLegendEntry entry : legendEntries) {
                    labelColumnWidth = Math.max(labelColumnWidth, textWidth(textFont, CELL_FONT_SIZE, entry.label()));
                }
                final float descriptionX = MARGIN + labelColumnWidth + CELL_PADDING_X * 4;

                for (final TranscriptLegendEntry entry : legendEntries) {
                    writeText(stream, textFont, CELL_FONT_SIZE, MARGIN, y, entry.label());
                    writeText(stream, textFont, CELL_FONT_SIZE, descriptionX, y, entry.description());
                    y -= ROW_HEIGHT * 0.9f;
                }

                writeFooter(stream, textFont, page);

            }

        }

        /**
         * Writes the shaded title banner spanning the content width, with {@code title} at
         * its left and {@code "Page {pageNumber} of {totalPages}"} at its right.
         *
         * @param stream the content stream to write to
         * @param font the font to render the banner text with
         * @param y the vertical offset of the banner's top edge
         * @param title the banner's title text
         * @param pageNumber this page's 1-based page number
         * @param totalPages the document's total page count
         * @return the vertical offset of the banner's bottom edge
         * @throws IOException if the banner cannot be written to {@code stream}
         */
        private static float writeBanner(
                final PDPageContentStream stream, final PDFont font, final float y,
                final String title, final int pageNumber, final int totalPages
        ) throws IOException {

            final float contentWidth = PDRectangle.A4.getWidth() - MARGIN * 2;
            final float bannerBottom = y - BANNER_HEIGHT;

            stream.setNonStrokingColor(BANNER_FILL);
            stream.addRect(MARGIN, bannerBottom, contentWidth, BANNER_HEIGHT);
            stream.fill();

            stream.setNonStrokingColor(Color.BLACK);
            final float baseline = bannerBottom + (BANNER_HEIGHT - TITLE_FONT_SIZE) / 2 + TITLE_FONT_SIZE * 0.2f;
            writeText(stream, font, TITLE_FONT_SIZE, MARGIN + CELL_PADDING_X * 2, baseline, title);

            final String pageLabel = "Page " + pageNumber + " of " + totalPages;
            final float pageLabelWidth = textWidth(font, HEADER_FONT_SIZE, pageLabel);
            writeText(stream, font, HEADER_FONT_SIZE, MARGIN + contentWidth - pageLabelWidth - CELL_PADDING_X * 2, baseline, pageLabel);

            return bannerBottom;

        }

        /**
         * Writes the shaded, bold column header row, bordered like every other row of the
         * table (see {@link #writeDataRow(PDPageContentStream, PDFont, float, List, float[], int)}).
         *
         * @param stream the content stream to write to
         * @param font the font to render the headers with
         * @param y the vertical offset of the row's top edge
         * @param headers the column headers, in display order
         * @param columnWidths each column's width, in the same order as {@code headers}
         * @return the vertical offset of the row's bottom edge
         * @throws IOException if the row cannot be written to {@code stream}
         */
        private static float writeHeaderRow(
                final PDPageContentStream stream, final PDFont font, final float y,
                final List<String> headers, final float[] columnWidths
        ) throws IOException {

            final float rowBottom = y - ROW_HEIGHT;

            stream.setNonStrokingColor(HEADER_FILL);
            stream.addRect(MARGIN, rowBottom, sum(columnWidths), ROW_HEIGHT);
            stream.fill();

            stream.setNonStrokingColor(Color.BLACK);
            stream.setStrokingColor(BORDER_COLOR);
            stream.setLineWidth(BORDER_WIDTH);

            float x = MARGIN;

            for (int i = 0; i < headers.size(); i++) {

                stream.addRect(x, rowBottom, columnWidths[i], ROW_HEIGHT);
                stream.stroke();

                final String text = truncateToWidth(font, HEADER_FONT_SIZE, headers.get(i), columnWidths[i] - CELL_PADDING_X * 2);
                writeText(stream, font, HEADER_FONT_SIZE, x + CELL_PADDING_X, rowBottom + 5, text);
                x += columnWidths[i];

            }

            return rowBottom;

        }

        /**
         * Writes one light-gray, bold section heading row (e.g. a semester's name),
         * spanning every column as a single bordered cell.
         *
         * @param stream the content stream to write to
         * @param font the font to render the heading with
         * @param y the vertical offset of the row's top edge
         * @param title the heading text
         * @param columnWidths each column's width, used only to size the spanning cell
         * @return the vertical offset of the row's bottom edge
         * @throws IOException if the heading cannot be written to {@code stream}
         */
        private static float writeSectionHeading(
                final PDPageContentStream stream, final PDFont font, final float y, final String title, final float[] columnWidths
        ) throws IOException {

            final float rowBottom = y - ROW_HEIGHT;
            final float tableWidth = sum(columnWidths);

            stream.setNonStrokingColor(HEADER_FILL);
            stream.addRect(MARGIN, rowBottom, tableWidth, ROW_HEIGHT);
            stream.fill();

            stream.setNonStrokingColor(Color.BLACK);
            stream.setStrokingColor(BORDER_COLOR);
            stream.setLineWidth(BORDER_WIDTH);
            stream.addRect(MARGIN, rowBottom, tableWidth, ROW_HEIGHT);
            stream.stroke();

            writeText(stream, font, SECTION_FONT_SIZE, MARGIN + CELL_PADDING_X, rowBottom + 5, title);

            return rowBottom;

        }

        /**
         * Writes one bordered data row, its background banded (see {@link #BAND_FILL}) on
         * every other {@code rowIndex} within the current section.
         *
         * @param stream the content stream to write to
         * @param font the font to render the cells with
         * @param y the vertical offset of the row's top edge
         * @param cells the cell values, in column order
         * @param columnWidths each column's width, in the same order as {@code cells}
         * @param rowIndex this row's position within its own section, for banding
         * @return the vertical offset of the row's bottom edge
         * @throws IOException if the row cannot be written to {@code stream}
         */
        private static float writeDataRow(
                final PDPageContentStream stream, final PDFont font, final float y,
                final List<String> cells, final float[] columnWidths, final int rowIndex
        ) throws IOException {

            final float rowBottom = y - ROW_HEIGHT;

            if (rowIndex % 2 == 1) {
                stream.setNonStrokingColor(BAND_FILL);
                stream.addRect(MARGIN, rowBottom, sum(columnWidths), ROW_HEIGHT);
                stream.fill();
            }

            stream.setNonStrokingColor(Color.BLACK);
            stream.setStrokingColor(BORDER_COLOR);
            stream.setLineWidth(BORDER_WIDTH);

            float x = MARGIN;

            for (int i = 0; i < cells.size() && i < columnWidths.length; i++) {

                stream.addRect(x, rowBottom, columnWidths[i], ROW_HEIGHT);
                stream.stroke();

                final String text = truncateToWidth(font, CELL_FONT_SIZE, cells.get(i), columnWidths[i] - CELL_PADDING_X * 2);
                writeText(stream, font, CELL_FONT_SIZE, x + CELL_PADDING_X, rowBottom + 5, text);
                x += columnWidths[i];

            }

            return rowBottom;

        }

        /**
         * Writes the page footer: a thin rule followed by a "Generated on {date}" line.
         *
         * @param stream the content stream to write to
         * @param font the font to render the footer text with
         * @param page the page being footed, for its width
         * @throws IOException if the footer cannot be written to {@code stream}
         */
        private static void writeFooter(final PDPageContentStream stream, final PDFont font, final PDPage page) throws IOException {

            final float ruleY = MARGIN + FOOTER_HEIGHT - 10;

            stream.setStrokingColor(RULE_COLOR);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, ruleY);
            stream.lineTo(page.getMediaBox().getWidth() - MARGIN, ruleY);
            stream.stroke();

            writeText(stream, font, 8f, MARGIN, MARGIN, "Generated on " + LocalDate.now().format(FOOTER_DATE_FORMAT));

        }

        /**
         * Writes a single line of text at the given baseline position.
         *
         * @param stream the content stream to write to
         * @param font the font to render the text with
         * @param fontSize the font size to render the text with
         * @param x the horizontal offset to write the text at
         * @param y the vertical offset (baseline) to write the text at
         * @param text the text to write
         * @throws IOException if the text cannot be written to {@code stream}
         */
        private static void writeText(
                final PDPageContentStream stream, final PDFont font, final float fontSize, final float x, final float y, final String text
        ) throws IOException {

            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();

        }

        /**
         * Shortens {@code text} with a trailing ellipsis so it fits within {@code maxWidth}
         * when rendered with {@code font} at {@code fontSize}, leaving it unchanged if it
         * already fits.
         *
         * @param font the font the text will be rendered with
         * @param fontSize the font size the text will be rendered with
         * @param text the text to fit
         * @param maxWidth the width to fit the text into
         * @return {@code text}, truncated with {@code "..."} if necessary
         * @throws IOException if {@code font}'s glyph widths cannot be read
         */
        private static String truncateToWidth(final PDFont font, final float fontSize, final String text, final float maxWidth) throws IOException {

            if (textWidth(font, fontSize, text) <= maxWidth) {
                return text;
            }

            final String ellipsis = "...";
            String truncated = text;

            while (!truncated.isEmpty() && textWidth(font, fontSize, truncated + ellipsis) > maxWidth) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }

            return truncated + ellipsis;

        }

        /**
         * Measures {@code text}'s rendered width with {@code font} at {@code fontSize}.
         *
         * @param font the font to measure with
         * @param fontSize the font size to measure with
         * @param text the text to measure
         * @return the rendered width
         * @throws IOException if {@code font}'s glyph widths cannot be read
         */
        private static float textWidth(final PDFont font, final float fontSize, final String text) throws IOException {
            return font.getStringWidth(text) / 1000 * fontSize;
        }

        /**
         * Sums an array of column widths, e.g. to find the total table width.
         *
         * @param widths the widths to sum
         * @return the sum
         */
        private static float sum(final float[] widths) {

            float total = 0f;
            for (final float width : widths) total += width;

            return total;

        }

    }

    /**
     * Exports a grouped transcript (see {@link TranscriptSection}) to an Excel workbook via
     * Apache POI: a shaded, bold header row, thin borders around every cell, and light
     * banding on alternating data rows, plus one light-gray, bold, merged section row per
     * group (e.g. a semester's name) and a blank row between sections. An optional legend
     * (e.g. a grading-scale key) goes on its own sheet. The Excel counterpart to
     * {@link TranscriptPDFExporter}'s PDF, sharing the same input shape so a caller can
     * offer both formats from the same already-built data.
     */
    public static final class TranscriptExcelExporter implements TranscriptExporter {

        /**
         * Font size, in points, of header, section and data cells.
         */
        private static final short CELL_FONT_SIZE = 11;

        /**
         * RGB color of every cell border.
         */
        private static final byte[] BORDER_COLOR = {(byte) 140, (byte) 140, (byte) 140};

        /**
         * RGB background fill of the header row and each section row.
         */
        private static final byte[] HEADER_FILL = {(byte) 230, (byte) 230, (byte) 230};

        /**
         * RGB background fill of alternating ("banded") data rows.
         */
        private static final byte[] BAND_FILL = {(byte) 247, (byte) 247, (byte) 247};

        /**
         * Writes {@code sections} to an Excel workbook at {@code output}, one column per
         * entry in {@code columnHeaders}, on a sheet named after {@code documentTitle}
         * (truncated and sanitized to fit Excel's sheet-name rules). {@code legendEntries}
         * (if not empty) is written to its own sheet named after {@code legendTitle}, its
         * label column auto-sized to the widest entry.
         *
         * @param documentTitle the transcript sheet's own name
         * @param columnHeaders the column headers, in display order
         * @param sections the grouped rows to write, in the order they should appear
         * @param legendTitle the legend sheet's name; ignored if {@code legendEntries} is empty
         * @param legendEntries the legend's entries, or empty to omit the legend sheet
         * @param output the file path the workbook is written to; overwritten if it already exists
         * @throws IOException if the workbook cannot be written to {@code output}
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code columnHeaders} is empty
         */
        @Override
        public void export(
                final String documentTitle,
                final List<String> columnHeaders,
                final List<TranscriptSection> sections,
                final String legendTitle,
                final List<TranscriptLegendEntry> legendEntries,
                final Path output
        ) throws IOException {

            Objects.requireNonNull(documentTitle, "@TranscriptExcelExporter.export: documentTitle must not be null");
            Objects.requireNonNull(columnHeaders, "@TranscriptExcelExporter.export: columnHeaders must not be null");
            Objects.requireNonNull(sections, "@TranscriptExcelExporter.export: sections must not be null");
            Objects.requireNonNull(legendEntries, "@TranscriptExcelExporter.export: legendEntries must not be null");
            Objects.requireNonNull(output, "@TranscriptExcelExporter.export: output must not be null");

            if (columnHeaders.isEmpty()) throw new IllegalArgumentException("@TranscriptExcelExporter.export: columnHeaders must not be empty");

            final Path outputPath = prepare(output);

            try (XSSFWorkbook workbook = new XSSFWorkbook()) {

                writeTranscriptSheet(workbook, documentTitle, columnHeaders, sections);

                if (!legendEntries.isEmpty()) {
                    writeLegendSheet(workbook, legendTitle, legendEntries);
                }

                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    workbook.write(out);
                }

            }

        }

        /**
         * Writes the main sheet: the bold, shaded header row, then one light-gray, bold,
         * merged section row per group followed by that group's own banded data rows and a
         * blank spacer row before the next group.
         *
         * @param workbook the workbook to add the sheet to
         * @param title the sheet's own name
         * @param headers the column headers, in display order
         * @param sections the grouped rows to write, in the order they should appear
         */
        private static void writeTranscriptSheet(
                final XSSFWorkbook workbook, final String title, final List<String> headers, final List<TranscriptSection> sections
        ) {

            final Sheet sheet = workbook.createSheet(sheetName(title));

            final XSSFCellStyle headerStyle = createRowStyle(workbook, true, HEADER_FILL);
            final XSSFCellStyle sectionStyle = createRowStyle(workbook, true, HEADER_FILL);
            final XSSFCellStyle cellStyle = createRowStyle(workbook, false, null);
            final XSSFCellStyle bandedStyle = createRowStyle(workbook, false, BAND_FILL);

            int rowIndex = 0;

            writeRow(sheet.createRow(rowIndex++), headers, headerStyle);

            for (final TranscriptSection section : sections) {

                final Row sectionRow = sheet.createRow(rowIndex);

                for (int i = 0; i < headers.size(); i++) {
                    sectionRow.createCell(i).setCellStyle(sectionStyle);
                }
                sectionRow.getCell(0).setCellValue(section.title());

                if (headers.size() > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, headers.size() - 1));
                }

                rowIndex++;

                int dataIndex = 0;
                for (final List<String> row : section.rows()) {
                    writeRow(sheet.createRow(rowIndex++), row, dataIndex++ % 2 == 1 ? bandedStyle : cellStyle);
                }

                rowIndex++; // blank row separating this section from the next

            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            sheet.createFreezePane(0, 1);

        }

        /**
         * Writes the legend sheet: {@code entries}' labels in a bold left column, their
         * descriptions in a plain right column.
         *
         * @param workbook the workbook to add the sheet to
         * @param title the sheet's own name
         * @param entries the legend's entries, in order
         */
        private static void writeLegendSheet(final XSSFWorkbook workbook, final String title, final List<TranscriptLegendEntry> entries) {

            final Sheet sheet = workbook.createSheet(sheetName(title));

            final Font labelFont = workbook.createFont();
            labelFont.setBold(true);
            final CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.setFont(labelFont);

            for (int i = 0; i < entries.size(); i++) {

                final TranscriptLegendEntry entry = entries.get(i);
                final Row row = sheet.createRow(i);

                final Cell labelCell = row.createCell(0);
                labelCell.setCellValue(entry.label());
                labelCell.setCellStyle(labelStyle);

                row.createCell(1).setCellValue(entry.description());

            }

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);

        }

        /**
         * Writes one row of cell values with the given style applied to every cell.
         *
         * @param row the sheet row to fill
         * @param values the cell values, in column order
         * @param style the style to apply to every cell in the row
         */
        private static void writeRow(final Row row, final List<String> values, final XSSFCellStyle style) {

            for (int i = 0; i < values.size(); i++) {

                final Cell cell = row.createCell(i);
                cell.setCellValue(values.get(i));
                cell.setCellStyle(style);

            }

        }

        /**
         * Builds a bordered, vertically-centered style for a header, section or data row,
         * with an optional background fill.
         *
         * @param workbook the workbook to create the style and font in
         * @param bold whether the row's text should be bold, i.e. whether it is a header or section row
         * @param fill the background fill color, as RGB bytes, or {@code null} for no fill
         * @return the row's cell style
         */
        private static XSSFCellStyle createRowStyle(final XSSFWorkbook workbook, final boolean bold, final byte[] fill) {

            final Font font = workbook.createFont();
            font.setBold(bold);
            font.setFontHeightInPoints(CELL_FONT_SIZE);

            final XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setVerticalAlignment(VerticalAlignment.CENTER);

            if (fill != null) {
                style.setFillForegroundColor(new XSSFColor(fill, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }

            final XSSFColor borderColor = new XSSFColor(BORDER_COLOR, null);

            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(borderColor);
            style.setBottomBorderColor(borderColor);
            style.setLeftBorderColor(borderColor);
            style.setRightBorderColor(borderColor);

            return style;

        }

        /**
         * Sanitizes {@code title} into a valid Excel sheet name: strips the characters
         * Excel forbids in one ({@code \ / ? * [ ]}), and truncates to its 31-character
         * limit.
         *
         * @param title the title to sanitize
         * @return the sanitized sheet name, or {@code "Sheet1"} if nothing valid remains
         */
        private static String sheetName(final String title) {

            final String sanitized = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
            final String truncated = sanitized.length() > 31 ? sanitized.substring(0, 31).trim() : sanitized;

            return truncated.isEmpty() ? "Sheet1" : truncated;

        }

    }

    /**
     * Zips a directory's entire contents into a single archive - a full,
     * format-agnostic backup of everything under some source location, as opposed to
     * the other exporters in this class, which each write one already-collected data
     * set as a table or transcript.
     */
    public static final class DirectoryZipExporter implements ArchiveExporter {

        /**
         * The directory whose contents are zipped by {@link #export(Path)}.
         */
        private final Path sourceDirectory;

        /**
         * Run once, before the directory is walked, e.g. to flush pending in-memory
         * changes to disk first so the archive reflects the latest state.
         */
        private final Runnable beforeExport;

        /**
         * Creates an exporter zipping {@code sourceDirectory}'s contents, with no
         * action run beforehand.
         *
         * @param sourceDirectory the directory whose contents {@link #export(Path)} zips
         * @throws NullPointerException if {@code sourceDirectory} is {@code null}
         */
        public DirectoryZipExporter(final Path sourceDirectory) {
            this(sourceDirectory, () -> {
            });
        }

        /**
         * Creates an exporter zipping {@code sourceDirectory}'s contents, running
         * {@code beforeExport} first.
         *
         * @param sourceDirectory the directory whose contents {@link #export(Path)} zips
         * @param beforeExport run once, before the directory is walked
         * @throws NullPointerException if {@code sourceDirectory} or {@code beforeExport} is {@code null}
         */
        public DirectoryZipExporter(final Path sourceDirectory, final Runnable beforeExport) {
            this.sourceDirectory = Objects.requireNonNull(sourceDirectory, "@DirectoryZipExporter: sourceDirectory must not be null");
            this.beforeExport = Objects.requireNonNull(beforeExport, "@DirectoryZipExporter: beforeExport must not be null");
        }

        /**
         * Runs {@link #beforeExport}, then zips every file under {@link #sourceDirectory},
         * preserving its directory structure, into an archive at {@code output}.
         *
         * @param output the file path the archive is written to; overwritten if it already exists
         * @throws IOException if {@link #sourceDirectory} does not exist, or the archive cannot be written
         * @throws NullPointerException if {@code output} is {@code null}
         */
        @Override
        public void export(final Path output) throws IOException {

            Objects.requireNonNull(output, "@DirectoryZipExporter.export: output must not be null");

            beforeExport.run();

            if (!Files.isDirectory(sourceDirectory)) {
                throw new IOException("No directory found at " + sourceDirectory);
            }

            final Path outputPath = prepare(output);

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputPath))) {

                try (Stream<Path> files = Files.walk(sourceDirectory).filter(Files::isRegularFile)) {

                    for (final Path file : (Iterable<Path>) files::iterator) {

                        final String entryName = sourceDirectory.relativize(file).toString().replace('\\', '/');

                        zip.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zip);
                        zip.closeEntry();

                    }

                }

            }

        }

    }

}
