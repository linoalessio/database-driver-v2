package de.lino.database.provider;

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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DatabaseProvider {

    /**
     * Shutdown the current database
     */
    void shutdown();

    /**
     * Create a new database section if not exists
     * Otherwise, the existing section will be returned
     *
     * @param name: section name
     * @return DatabaseSection
     */
    DatabaseSection createSection(@NotNull String name);

    /**
     * Delete if the section does exist
     * Otherwise, the process will be stopped
     *
     * @param name: section name
     */
    void deleteSection(@NotNull String name);

    /**
     * Check whether a section exists
     * @param name: section name
     * @return true if section exists, otherwise false
     */
    boolean existsSection(@NotNull String name);

    /**
     * List all existing database sections
     * @return an unmodifiable list of all sections
     */
    @UnmodifiableView
    List<DatabaseSection> getSections();

    /**
     * Get a section by name
     * @param name: section name
     * @return DatabaseSection
     */
    DatabaseSection getSection(@NotNull String name);

    /**
     * Remove all sections from the provider
     */
    void clear();

    /**
     * Execute shutdown process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> shutdownAsync() {
        return CompletableFuture.runAsync(this::shutdown);
    }

    /**
     * Execute create process async
     * @return CompletableFuture, type DatabaseSection
     */
    default CompletableFuture<DatabaseSection> createSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> createSection(name));
    }

    /**
     * Execute delete process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> deleteSectionAsync(@NotNull String name) {
        return CompletableFuture.runAsync(() -> deleteSection(name));
    }

    /**
     * Execute query process async
     * @return CompletableFuture, type Boolean
     */
    default CompletableFuture<Boolean> existsSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> existsSection(name));
    }

    /**
     * Execute section collecting process async
     * @return CompletableFuture, type List<DatabaseSection>
     */
    default CompletableFuture<List<DatabaseSection>> getSectionsAsync() {
        return CompletableFuture.supplyAsync(this::getSections);
    }

    /**
     * Execute get section process async
     * @return CompletableFuture, type DatabaseSection
     */
    default CompletableFuture<DatabaseSection> getSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> getSection(name));
    }

    /**
     * Execute clear process async
     * @return CompletableFuture, type Void
     */
    default CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(this::clear);
    }

}
