package de.lino.database.export.transcript;

/**
 * One entry of a closing legend, shared by every built-in {@link TranscriptExporter}
 * implementation - {@code ExportCoordinator.TranscriptPDFExporter},
 * {@code ExportCoordinator.TranscriptExcelExporter}, {@code ExportCoordinator.TranscriptCSVExporter},
 * {@code ExportCoordinator.TranscriptXMLExporter}, {@code ExportCoordinator.TranscriptJsonExporter}
 * and {@code ExportCoordinator.TranscriptDocxExporter} - e.g. a grading-scale key:
 * {@code label} in a left column, {@code description} in a right column, aligned
 * consistently across every entry.
 *
 * @param label the entry's left-column text, e.g. a grade range
 * @param description the entry's right-column text, e.g. what the grade range means
 */
public record TranscriptLegendEntry(String label, String description) {
}
