package de.lino.database.export.archiv;

import de.lino.database.export.data.DataExporter;
import de.lino.database.export.transcript.TranscriptExporter;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The shape of a whole-directory, format-agnostic archive exporter, e.g.
 * {@link ExportCoordinator.DirectoryZipExporter}: writing everything under some source
 * location to a single file at {@code output}, as opposed to {@link DataExporter} and
 * {@link TranscriptExporter}, which each write one already-collected data set as a
 * table.
 *
 * <p>{@link ExportCoordinator.DirectoryZipExporter} implements this interface directly;
 * being a {@link FunctionalInterface}, it can just as well be satisfied with a lambda
 * or method reference for a one-off implementation.
 */
@FunctionalInterface
public interface ArchiveExporter {

    /**
     * Writes an archive to {@code output}.
     *
     * @param output the file path the archive is written to; overwritten if it already exists
     * @throws IOException if the archive cannot be written to {@code output}
     */
    void export(Path output) throws IOException;

}
