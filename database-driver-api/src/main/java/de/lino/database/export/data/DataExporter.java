package de.lino.database.export.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * The shared API every format-specific, flat-table exporter implements: writing an
 * arbitrary data set to a file as a table, with one column per header and one table
 * row per element of the data set. No default implementation ships with this module -
 * a caller that needs a plain, ungrouped table export supplies its own {@link DataExporter}
 * and injects it into an {@link ExportCoordinator}.
 *
 * <p>Callers can program against this interface rather than a concrete exporter,
 * e.g. to export the same data set to several formats without branching on which one.
 */
public interface DataExporter {

    /**
     * Writes {@code rows} to a file at {@code output}, with one column per entry in
     * {@code headers} and one table row per element of {@code rows}.
     *
     * @param <T> the type of the exported rows
     * @param rows the data set to export, in the order it should appear
     * @param headers the column headers, in display order
     * @param rowMapper turns a row into one cell value per header, in the same order as {@code headers}
     * @param title the document title
     * @param output the file path the export is written to; overwritten if it already exists
     * @throws IOException if the export cannot be written to {@code output}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code headers} is empty
     */
    <T> void export(List<T> rows, List<String> headers, Function<T, List<String>> rowMapper, String title, Path output) throws IOException;

}
