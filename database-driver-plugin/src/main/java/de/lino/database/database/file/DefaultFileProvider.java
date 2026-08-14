package de.lino.database.database.file;

import de.lino.database.json.file.FileProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The concrete, NIO-backed {@link FileProvider}: every operation is implemented directly on top
 * of {@link Files} (or, for bulk copies, {@link Files#walkFileTree}), rather than the legacy
 * {@code java.io} file API. Installs itself as {@link FileProvider}'s generated
 * {@code getInstance()} accessor on construction.
 * <p>
 * Stateless beyond the inherited singleton instance, so every method is safe to call
 * concurrently from multiple threads; thread-safety of the operations themselves is delegated
 * entirely to the filesystem.
 */
public class DefaultFileProvider extends FileProvider {

    /**
     * Installs this instance as {@link FileProvider}'s generated {@code getInstance()} accessor.
     */
    public DefaultFileProvider() {
        setInstance(this);
    }

    @Override
    public void deleteFile(@NotNull final File file) {
        deleteFile(file.toPath());
    }

    @Override
    public void deleteFile(@NotNull final Path file) {

        try {
            Files.deleteIfExists(file);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void createFile(@NotNull final Path path) {

        if (Files.exists(path)) return;

        try {

            final Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            Files.createFile(path);

        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void createFile(@NotNull final File file) {
        createFile(file.toPath());
    }

    @Override
    public void updateFile(@NotNull final Path file) {
        deleteFile(file);
        createFile(file);
    }

    @Override
    public void updateFile(@NotNull final File file) {
        deleteFile(file);
        createFile(file);
    }

    @Override
    public void rename(@NotNull final File file, @NotNull final String newName) {

        try {
            Files.move(file.toPath(), Path.of(newName));
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void createDirectory(@Nullable final Path path) {

        if (path == null) return;

        try {
            Files.createDirectories(path);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void doCopy(@NotNull final String from, @NotNull final String target) {

        try {
            Files.copy(Path.of(from), Path.of(target), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void deleteAllFilesInDirectory(@NotNull final Path dirPath) {

        List<Path> files = List.of();

        try (final Stream<Path> entries = Files.list(dirPath)) {
            files = entries.filter(Files::isRegularFile).toList();
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

        // Listed and closed above before deleting, rather than deleting while the directory
        // stream is still open - mutating a directory while iterating a live handle on it is
        // platform-dependent behavior under NIO.
        files.forEach(this::deleteFile);

    }

    @Override
    public void deleteDirectory(@NotNull final Path dirPath) {

        List<Path> children = List.of();

        try (final Stream<Path> entries = Files.list(dirPath)) {
            children = entries.toList();
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

        for (final Path path : children) {
            if (Files.isDirectory(path)) {
                deleteDirectory(path);
            } else {
                deleteFile(path);
            }
        }

        deleteFile(dirPath);

    }

    @Override
    public void recreateDirectory(@NotNull final Path path) {

        if (Files.exists(path)) {
            if (path.toFile().isDirectory()) {
                deleteDirectory(path);
            } else {
                deleteFile(path.toFile());
            }
        }

        createDirectory(path);

    }

    @Override
    public void copyDirectory(@NotNull final Path path, @NotNull final Path target) {
        copyDirectory(path, target, Set.of());
    }

    @Override
    public void copyDirectory(@NotNull final Path path, @NotNull final Path target, @NotNull final Collection<String> excludedFiles) {

        final Set<String> excluded = Set.copyOf(excludedFiles);

        try {

            Files.walkFileTree(path, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {

                    if (excluded.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;

                    final Path targetFile = target.resolve(path.relativize(file));
                    final Path parent = targetFile.getParent();

                    if (parent != null) Files.createDirectories(parent);

                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;

                }
            });

        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

}
