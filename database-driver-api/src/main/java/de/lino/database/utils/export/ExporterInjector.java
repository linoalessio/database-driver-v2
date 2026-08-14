package de.lino.database.utils.export;

import de.lino.database.utils.export.archiv.ArchiveExporter;
import de.lino.database.utils.export.data.DataExporter;

/**
 * The injection contract {@link ExportCoordinator} implements. Rather than receiving
 * its exporters through its constructor or through per-call setter methods of its own
 * devising, each is handed in through one of these shared setter methods - "interface
 * injection", the third classic form of dependency injection alongside constructor and
 * setter injection, distinguished by the setters being defined once on a shared
 * interface rather than being specific to whichever class happens to need them.
 *
 * <p>Any class - not just {@link ExportCoordinator} - can implement this interface to
 * become wireable against {@link DataExporter} and {@link ArchiveExporter} the same
 * way; nothing about it is specific to any one application. Grouped-transcript exports
 * are deliberately not part of this contract: {@link ExportCoordinator} resolves which
 * transcript implementation to use dynamically, per call, rather than through an
 * injected instance - see {@link ExportCoordinator#exportTranscript}.
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
     * Injects the archive exporter used by subsequent calls, e.g. a
     * {@link ExportCoordinator.DirectoryZipExporter}.
     *
     * @param archiveExporter the exporter to inject
     * @throws NullPointerException if {@code archiveExporter} is {@code null}
     */
    void injectArchiveExporter(ArchiveExporter archiveExporter);

}
