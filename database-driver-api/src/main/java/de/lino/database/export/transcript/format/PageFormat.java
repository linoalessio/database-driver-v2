package de.lino.database.export.transcript.format;

/**
 * A standard ISO 216 page size an export can be rendered at, e.g. by
 * {@code ExportCoordinator.TranscriptPDFExporter}, {@code ExportCoordinator.TranscriptExcelExporter}
 * or {@code ExportCoordinator.TranscriptDocxExporter} - every {@link de.lino.database.export.transcript.TranscriptExporter}
 * implementation accepts a {@link PageLayout}, but only these three actually render
 * pages and so are the only ones a chosen format has any visible effect on. Paired with
 * a {@link PageOrientation} via {@link PageLayout}.
 */
public enum PageFormat {

    /**
     * 297 x 420 mm.
     */
    A3,

    /**
     * 210 x 297 mm.
     */
    A4,

    /**
     * 148 x 210 mm.
     */
    A5

}
