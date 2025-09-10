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
