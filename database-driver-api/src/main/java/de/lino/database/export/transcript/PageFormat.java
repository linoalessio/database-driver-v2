package de.lino.database.export.transcript;

/**
 * A standard ISO 216 page size an export can be rendered at, e.g. by
 * {@link ExportCoordinator.TranscriptPDFExporter} or
 * {@link ExportCoordinator.TranscriptExcelExporter}. Paired with a {@link PageOrientation}
 * via {@link PageLayout}.
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
