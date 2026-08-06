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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a single connected database backend (e.g. a MySQL instance, a MongoDB database or a
 * local JSON file store) and exposes management operations over its {@link DatabaseSection}s.
 * <p>
 * A section roughly corresponds to a table, collection or directory depending on the concrete
 * database technology. Every synchronous operation declared here has a corresponding
 * {@code *Async} default method that executes the same logic on the common
 * {@link CompletableFuture} pool.
 */
public interface DatabaseProvider {

    /**
     * Shutdown the current database.
     */
    void shutdown();

    /**
     * Create a new database section if not exists.
     * Otherwise, the existing section will be returned.
     *
     * @param name section name
     * @return the newly created, or already existing, {@link DatabaseSection}
     */
    DatabaseSection createSection(@NotNull String name);

    /**
     * Delete if the section does exist.
     * Otherwise, the process will be stopped.
     *
     * @param name section name
     */
    void deleteSection(@NotNull String name);

    /**
     * Check whether a section exists.
     *
     * @param name section name
     * @return true if section exists, otherwise false
     */
    boolean existsSection(@NotNull String name);

    /**
     * List all existing database sections.
     *
     * @return an unmodifiable list of all sections
     */
    @UnmodifiableView
    List<DatabaseSection> getSections();

    /**
     * Get a section by name.
     *
     * @param name section name
     * @return an {@link Optional} containing the matching {@link DatabaseSection}, or empty if no
     * section exists under the given name
     */
    Optional<DatabaseSection> getSection(@NotNull String name);

    /**
     * Remove all sections from the provider.
     */
    void clear();

    /**
     * Execute the {@link #shutdown()} process async.
     *
     * @return a {@link CompletableFuture} that completes once the provider has been shut down
     */
    default CompletableFuture<Void> shutdownAsync() {
        return CompletableFuture.runAsync(this::shutdown);
    }

    /**
     * Execute the {@link #createSection(String)} process async.
     *
     * @param name section name
     * @return a {@link CompletableFuture} resolving to the newly created, or already existing,
     * {@link DatabaseSection}
     */
    default CompletableFuture<DatabaseSection> createSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> createSection(name));
    }

    /**
     * Execute the {@link #deleteSection(String)} process async.
     *
     * @param name section name
     * @return a {@link CompletableFuture} that completes once the section has been deleted
     */
    default CompletableFuture<Void> deleteSectionAsync(@NotNull String name) {
        return CompletableFuture.runAsync(() -> deleteSection(name));
    }

    /**
     * Execute the {@link #existsSection(String)} query process async.
     *
     * @param name section name
     * @return a {@link CompletableFuture} resolving to {@code true} if the section exists,
     * {@code false} otherwise
     */
    default CompletableFuture<Boolean> existsSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> existsSection(name));
    }

    /**
     * Execute the {@link #getSections()} collecting process async.
     *
     * @return a {@link CompletableFuture} resolving to an unmodifiable list of all sections
     */
    default CompletableFuture<List<DatabaseSection>> getSectionsAsync() {
        return CompletableFuture.supplyAsync(this::getSections);
    }

    /**
     * Execute the {@link #getSection(String)} process async.
     *
     * @param name section name
     * @return a {@link CompletableFuture} resolving to an {@link Optional} containing the
     * matching {@link DatabaseSection}, or empty if none exists under the given name
     */
    default CompletableFuture<Optional<DatabaseSection>> getSectionAsync(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> getSection(name));
    }

    /**
     * Execute the {@link #clear()} process async.
     *
     * @return a {@link CompletableFuture} that completes once every section has been removed
     */
    default CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(this::clear);
    }

}
