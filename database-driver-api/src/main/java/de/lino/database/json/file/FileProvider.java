package de.lino.database.json.file;

/*
 * MIT License
 *
 * Copyright (c) lino, 08.09.2025
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Abstraction over the filesystem operations used throughout the driver (creating, deleting,
 * renaming and copying files and directories), so that {@link de.lino.database.json.JsonDocument}
 * and the various providers do not depend on a concrete filesystem implementation directly.
 * <p>
 * A single, globally accessible instance is installed via {@link #setInstance(FileProvider)} and
 * retrieved through the generated {@code getInstance()} accessor.
 */
@Getter
public abstract class FileProvider {

    /**
     * The globally accessible instance of this provider, installed via
     * {@link #setInstance(FileProvider)} and exposed through the generated
     * {@code getInstance()} accessor.
     */
    @Getter
    @Nullable
    protected static FileProvider instance;

    /**
     * Installs the given provider as the globally accessible instance returned by the generated
     * {@code getInstance()} accessor.
     *
     * @param instance the provider instance to install
     */
    protected static void setInstance(@NotNull FileProvider instance) {
        FileProvider.instance = instance;
    }

    /**
     * Deletes the given file, if it exists.
     *
     * @param file the file to delete
     */
    public abstract void deleteFile(@NotNull File file);

    /**
     * Deletes the file at the given path, if it exists.
     *
     * @param file the path of the file to delete
     */
    public abstract void deleteFile(@NotNull Path file);

    /**
     * Creates the given file, including any missing parent directories, if it does not exist yet.
     *
     * @param file the path of the file to create
     */
    public abstract void createFile(@NotNull Path file);

    /**
     * Creates the given file, including any missing parent directories, if it does not exist yet.
     *
     * @param file the file to create
     */
    public abstract void createFile(@NotNull File file);

    /**
     * Recreates the given file, first deleting it if present and then creating it anew.
     *
     * @param file the path of the file to update
     */
    public abstract void updateFile(@NotNull Path file);

    /**
     * Recreates the given file, first deleting it if present and then creating it anew.
     *
     * @param file the file to update
     */
    public abstract void updateFile(@NotNull File file);

    /**
     * Renames the given file.
     *
     * @param file    the file to rename
     * @param newName the new name of the file
     */
    public abstract void rename(@NotNull File file, @NotNull String newName);

    /**
     * Creates the given directory, including any missing parent directories.
     *
     * @param path the directory to create, or {@code null} to do nothing
     */
    public abstract void createDirectory(@Nullable Path path);

    /**
     * Copies a single file from one location to another.
     *
     * @param from   the path of the file to copy
     * @param target the destination path
     */
    public abstract void doCopy(@NotNull String from, @NotNull String target);

    /**
     * Deletes every file directly contained in the given directory, without recursing into
     * subdirectories.
     *
     * @param dirPath the directory whose files should be deleted
     */
    public abstract void deleteAllFilesInDirectory(@NotNull Path dirPath);

    /**
     * Recursively deletes the given directory and all of its contents.
     *
     * @param dirPath the directory to delete
     */
    public abstract void deleteDirectory(@NotNull Path dirPath);

    /**
     * Deletes the given directory (or file) if it exists, and then recreates it as an empty
     * directory.
     *
     * @param path the directory to recreate
     */
    public abstract void recreateDirectory(@NotNull Path path);

    /**
     * Recursively copies a directory's contents to another location.
     *
     * @param path   the source directory
     * @param target the destination directory
     */
    public abstract void copyDirectory(@NotNull Path path, @NotNull Path target);

    /**
     * Recursively copies a directory's contents to another location, skipping any file whose name
     * is contained in {@code excludedFiles}.
     *
     * @param path          the source directory
     * @param target        the destination directory
     * @param excludedFiles the file names to skip while copying
     */
    public abstract void copyDirectory(@NotNull Path path, @NotNull Path target, @NotNull Collection<String> excludedFiles);

}
