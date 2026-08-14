package de.lino.database.utils.export;

import de.lino.database.utils.export.transcript.TranscriptExporter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * The transcript file formats {@link ExportCoordinator} ships a built-in
 * {@link TranscriptExporter} for. Each constant
 * carries the file extension its format is conventionally saved with; see
 * {@link #fromSuffix}, which {@link ExportCoordinator#exportTranscript} uses to
 * auto-detect a call's output format from its file extension, and
 * {@link #adjustSuffixToFile}, its inverse, for building such a path in the first
 * place.
 */
@Getter
@AllArgsConstructor
public enum ExportType {

    /**
     * Portable Document Format, written by {@code ExportCoordinator.TranscriptPDFExporter}.
     */
    PDF("pdf"),

    /**
     * Office Open XML Workbook (.xlsx), written by {@code ExportCoordinator.TranscriptExcelExporter}.
     */
    EXCEL("xlsx"),

    /**
     * Comma-separated values, written by {@code ExportCoordinator.TranscriptCSVExporter}.
     */
    CSV("csv"),

    /**
     * Extensible Markup Language, written by {@code ExportCoordinator.TranscriptXMLExporter}.
     */
    XML("xml"),

    /**
     * JavaScript Object Notation, written by {@code ExportCoordinator.TranscriptJsonExporter}.
     */
    JSON("json"),

    /**
     * Office Open XML Document (.docx), written by {@code ExportCoordinator.TranscriptDocxExporter}.
     */
    DOCX("docx");

    /**
     * The file extension this format is conventionally saved with, without a leading dot
     * (e.g. {@code "pdf"}).
     */
    @NonNull
    private final String suffix;

    /**
     * Appends {@link #suffix} to {@code path}, separated by a dot, e.g.
     * {@code PDF.adjustSuffixToFile(Path.of("transcript"))} returns a path for
     * {@code "transcript.pdf"}.
     *
     * @param path the extension-less file path to append {@link #suffix} to
     * @return a new path equal to {@code path} with {@code ".{@link #suffix}"} appended
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public Path adjustSuffixToFile(@NonNull final Path path) {
        return Path.of(path + "." + suffix);
    }

    /**
     * Resolves the {@link ExportType} whose {@link #suffix} matches {@code file}'s file
     * extension (case-insensitively), e.g. {@code fromSuffix("transcript.PDF")} returns
     * {@link #PDF}.
     *
     * @param file a file name or path, with a file extension
     * @return the matching {@link ExportType}, or an empty {@link Optional} if {@code file}
     *         has no extension or it matches none of this enum's {@link #suffix} values
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static Optional<ExportType> fromSuffix(@NonNull final String file) {

        final int dotIndex = file.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == file.length() - 1) {
            return Optional.empty();
        }

        final String extension = file.substring(dotIndex + 1);

        return Arrays.stream(ExportType.values()).filter(exportType -> exportType.getSuffix().equalsIgnoreCase(extension)).findFirst();

    }

}
