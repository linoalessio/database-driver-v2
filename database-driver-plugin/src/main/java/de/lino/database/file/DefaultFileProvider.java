package de.lino.database.file;

import com.google.common.collect.Lists;
import de.lino.database.json.file.FileProvider;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;

public class DefaultFileProvider extends FileProvider {

    public DefaultFileProvider() {
        setInstance(this);
    }

    @Override
    public void deleteFile(File file) {
        deleteFile(file.toPath());
    }

    @Override
    public void deleteFile(Path file) {

        try {
            Files.deleteIfExists(file);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void createFile(Path path) {

        if (!Files.exists(path)) {
            final Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                try {
                    Files.createDirectories(parent);
                    Files.createFile(path);
                } catch (final IOException exception) {
                    exception.printStackTrace();
                }
            }
        }

    }

    @Override
    public void createFile(File file) {
        createFile(file.toPath());
    }

    @Override
    public void updateFile(Path file) {
        deleteFile(file);
        createFile(file);
    }

    @Override
    public void updateFile(File file) {
        deleteFile(file);
        createFile(file);
    }

    @Override
    public void rename(File file, String newName) {
        file.renameTo(new File(newName));
    }

    @Override
    public void createDirectory(@Nullable Path path) {

        if (path == null) return;
        try {
            Files.createDirectories(path);
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void doCopy(String from, String target) {

        try (final FileInputStream fileInputStream = new FileInputStream(from); final FileOutputStream fileOutputStream = new FileOutputStream(target)) {

            final byte[] buffer = new byte[1024];
            int length;
            while ((length = fileInputStream.read(buffer)) > 0) fileOutputStream.write(buffer, 0, length);

        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void deleteAllFilesInDirectory(Path dirPath) {

        try (final DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dirPath)) {
            for (Path path : directoryStream) if (!Files.isDirectory(path)) deleteFile(path);
        } catch (IOException exception) {
            exception.printStackTrace();
        }

    }

    @Override
    public void deleteDirectory(Path dirPath) {

        try (final DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dirPath)) {
            for (final Path path : directoryStream) {
                if (Files.isDirectory(path)) {
                    deleteDirectory(path);
                } else {
                    deleteFile(path);
                }
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }

        deleteFile(dirPath);

    }

    @Override
    public void recreateDirectory(Path path) {

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
    public void copyDirectory(Path path, Path target) {
        copyDirectory(path, target, Lists.newArrayList());
    }

    @Override
    public void copyDirectory(Path path, Path target, Collection<String> excludedFiles) {

        try {

            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (excludedFiles.stream().anyMatch(e -> e.equals(file.toFile().getName())))
                        return FileVisitResult.CONTINUE;

                    final Path targetFile = Paths.get(target.toString(), path.relativize(file).toString());
                    final Path parent = targetFile.getParent();

                    if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);

                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException exception) {
            exception.printStackTrace();
        }

    }

}
