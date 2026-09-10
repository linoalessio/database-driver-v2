package de.lino.database.database;

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
     * <p>
     * <b>This overload always means {@link CacheMode#FULL}</b> (unless a different
     * {@link SectionConfig} was already registered for {@code name} via
     * {@link #createSection(String, SectionConfig)}): the section holds every entry in memory,
     * warm by the time this returns - the behavior every consumer written before per-section
     * cache configuration existed was built against.
     *
     * @param name section name
     * @return the newly created, or already existing, {@link DatabaseSection}
     */
    DatabaseSection createSection(@NotNull String name);

    /**
     * Like {@link #createSection(String)}, with an explicit cache configuration deciding how
     * the section holds entries in memory (see {@link CacheMode} for the trade-offs). Repeating
     * the call with the configuration the section already runs under returns the existing
     * instance; a <em>different</em> configuration replaces the instance - the newest
     * declaration wins - dropping whatever cache state the old one held.
     * <p>
     * This is a {@code default} method only so third-party {@link DatabaseProvider}
     * implementations written before it existed keep compiling: the fallback ignores
     * {@code config} entirely and behaves exactly like {@link #createSection(String)}. Every
     * provider shipped by this library's plugin module overrides it with a real implementation;
     * do not rely on the fallback for anything beyond source compatibility.
     *
     * @param name   section name
     * @param config how the section should hold entries in memory
     * @return the newly created, or already existing, {@link DatabaseSection}
     */
    default DatabaseSection createSection(@NotNull String name, @NotNull SectionConfig config) {
        return createSection(name);
    }

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
     * Remove all sections from the database.
     */
    void clear();

    /**
     * Discards this database's own cached view of which sections exist and rebuilds it
     * from the backing store, picking up sections created or removed by something other
     * than this database itself (e.g. a backup restored directly onto disk while this
     * database was already running).
     * <p>
     * Every implementation shipped by this module caches its section list beyond what
     * each write already keeps in sync (see each implementation's own class-level
     * documentation), so a genuine re-read of the backing store is required here, not a
     * no-op - {@link #getSections()} / {@link #getSection(String)} would otherwise never
     * reflect a change made outside this database. Does not, by itself, affect any
     * {@link DatabaseSection} obtained from this database before the call, since the
     * rebuilt section list holds entirely new instances; re-fetch it via
     * {@link #getSection(String)} afterward.
     */
    void reload();

    /**
     * Execute the {@link #shutdown()} process async.
     *
     * @return a {@link CompletableFuture} that completes once the database has been shut down
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
     * Execute the {@link #createSection(String, SectionConfig)} process async.
     *
     * @param name   section name
     * @param config how the section should hold entries in memory
     * @return a {@link CompletableFuture} resolving to the newly created, or already existing,
     * {@link DatabaseSection}
     */
    default CompletableFuture<DatabaseSection> createSectionAsync(@NotNull String name, @NotNull SectionConfig config) {
        return CompletableFuture.supplyAsync(() -> createSection(name, config));
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

    /**
     * Execute the {@link #reload()} process async.
     *
     * @return a {@link CompletableFuture} that completes once this database has been reloaded
     */
    default CompletableFuture<Void> reloadAsync() {
        return CompletableFuture.runAsync(this::reload);
    }

}
