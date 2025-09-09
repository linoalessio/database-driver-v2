package de.lino.database.json.file;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

@Getter
public abstract class FileProvider {

    @Getter
    protected static FileProvider instance;

    protected static void setInstance(@NotNull FileProvider instance) {
        FileProvider.instance = instance;
    }

    public abstract void deleteFile(File file);

    public abstract void deleteFile(Path file);

    public abstract void createFile(Path file);

    public abstract void createFile(File file);

    public abstract void updateFile(Path file);

    public abstract void updateFile(File file);

    public abstract void rename(File file, String newName);

    public abstract void createDirectory(@Nullable Path path);

    public abstract void doCopy(String from, String target);

    public abstract void deleteAllFilesInDirectory(Path dirPath);

    public abstract void deleteDirectory(Path dirPath);

    public abstract void recreateDirectory(Path path);

    public abstract void copyDirectory(Path path, Path target);

    public abstract void copyDirectory(Path path, Path target, Collection<String> excludedFiles);

}
