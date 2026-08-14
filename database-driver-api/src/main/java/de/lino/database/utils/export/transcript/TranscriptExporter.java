package de.lino.database.utils.export.transcript;

import de.lino.database.utils.export.data.DataExporter;
import de.lino.database.utils.export.transcript.format.PageLayout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared shape of a grouped, "transcript-style" exporter, e.g.
 * {@code ExportCoordinator.TranscriptPDFExporter} or
 * {@code ExportCoordinator.TranscriptExcelExporter}: writing an already-grouped,
 * already-formatted data set - {@link TranscriptSection}s under a shared set of column
 * headers, plus an optional closing {@link TranscriptLegendEntry} legend - to a file.
 * The transcript counterpart of {@link DataExporter}'s flat-table contract.
 *
 * <p>{@code ExportCoordinator.TranscriptPDFExporter}, {@code ExportCoordinator.TranscriptExcelExporter},
 * {@code ExportCoordinator.TranscriptCSVExporter}, {@code ExportCoordinator.TranscriptXMLExporter},
 * {@code ExportCoordinator.TranscriptJsonExporter} and {@code ExportCoordinator.TranscriptDocxExporter}
 * implement this interface directly (each private to {@code ExportCoordinator}, only
 * reachable through it) - see {@code ExportCoordinator.exportTranscript}, which
 * auto-detects between them; being a {@link FunctionalInterface}, it can just as well
 * be satisfied with a lambda or method reference for a one-off implementation.
 */
@FunctionalInterface
public interface TranscriptExporter {

    /**
     * Writes {@code sections} to a file at {@code output}, one column per entry in
     * {@code columnHeaders}, with an optional closing legend.
     *
     * @param documentTitle the document title shown on every page/sheet
     * @param columnHeaders the column headers, in display order
     * @param sections the grouped rows to write, in the order they should appear
     * @param legendTitle the closing legend's heading; ignored if {@code legendEntries} is empty
     * @param legendEntries the closing legend's entries, or empty to omit it
     * @param pageLayout the page size and orientation to render the export at
     * @param output the file path the export is written to; overwritten if it already exists
     * @throws IOException if the export cannot be written to {@code output}
     */
    void export(
            String documentTitle,
            List<String> columnHeaders,
            List<TranscriptSection> sections,
            String legendTitle,
            List<TranscriptLegendEntry> legendEntries,
            PageLayout pageLayout,
            Path output
    ) throws IOException;

}
