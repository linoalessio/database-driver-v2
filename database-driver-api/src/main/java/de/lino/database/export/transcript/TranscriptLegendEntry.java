package de.lino.database.export.transcript;

/**
 * One entry of a closing legend, shared by {@link ExportCoordinator.TranscriptPDFExporter}
 * (PDF) and {@link ExportCoordinator.TranscriptExcelExporter} (Excel), e.g. a
 * grading-scale key: {@code label} in a left column, {@code description} in a right
 * column, aligned consistently across every entry.
 *
 * @param label the entry's left-column text, e.g. a grade range
 * @param description the entry's right-column text, e.g. what the grade range means
 */
public record TranscriptLegendEntry(String label, String description) {
}
