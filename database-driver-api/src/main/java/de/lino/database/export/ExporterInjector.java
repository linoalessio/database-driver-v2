package de.lino.database.export;

import de.lino.database.export.archiv.ArchiveExporter;
import de.lino.database.export.data.DataExporter;
import de.lino.database.export.transcript.TranscriptExporter;

/**
 * The injection contract {@link ExportCoordinator} implements. Rather than receiving
 * its three exporters through its constructor or through per-call setter methods of
 * its own devising, each is handed in through one of these shared setter methods -
 * "interface injection", the third classic form of dependency injection alongside
 * constructor and setter injection, distinguished by the setters being defined once
 * on a shared interface rather than being specific to whichever class happens to need
 * them.
 *
 * <p>Any class - not just {@link ExportCoordinator} - can implement this interface to
 * become wireable against {@link DataExporter}, {@link TranscriptExporter} and
 * {@link ArchiveExporter} the same way; nothing about it is specific to any one
 * application.
 */
public interface ExporterInjector {

    /**
     * Injects the flat-table exporter used by subsequent calls.
     *
     * @param dataExporter the exporter to inject
     * @throws NullPointerException if {@code dataExporter} is {@code null}
     */
    void injectDataExporter(DataExporter dataExporter);

    /**
     * Injects the grouped-transcript exporter used by subsequent calls, e.g. an
     * {@link ExportCoordinator.TranscriptPDFExporter} or
     * {@link ExportCoordinator.TranscriptExcelExporter}.
     *
     * @param transcriptExporter the exporter to inject
     * @throws NullPointerException if {@code transcriptExporter} is {@code null}
     */
    void injectTranscriptExporter(TranscriptExporter transcriptExporter);

    /**
     * Injects the archive exporter used by subsequent calls, e.g. a
     * {@link ExportCoordinator.DirectoryZipExporter}.
     *
     * @param archiveExporter the exporter to inject
     * @throws NullPointerException if {@code archiveExporter} is {@code null}
     */
    void injectArchiveExporter(ArchiveExporter archiveExporter);

}
