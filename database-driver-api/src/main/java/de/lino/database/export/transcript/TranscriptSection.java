package de.lino.database.export.transcript;

import java.util.List;

/**
 * One labeled group of rows, shared by every built-in {@link TranscriptExporter}
 * implementation - {@code ExportCoordinator.TranscriptPDFExporter},
 * {@code ExportCoordinator.TranscriptExcelExporter}, {@code ExportCoordinator.TranscriptCSVExporter},
 * {@code ExportCoordinator.TranscriptXMLExporter}, {@code ExportCoordinator.TranscriptJsonExporter}
 * and {@code ExportCoordinator.TranscriptDocxExporter} - so all six formats can be built
 * from the same already-grouped, already-formatted input: printed as a heading followed
 * by its own data rows, e.g. one semester's exams.
 *
 * @param title the section's heading, e.g. a semester's name
 * @param rows the section's rows, one cell value per column header, in the same order
 */
public record TranscriptSection(String title, List<List<String>> rows) {
}
