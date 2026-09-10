package de.lino.database.database;

import com.google.common.collect.Maps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The single shared section-management logic behind every {@link DatabaseProvider} shipped by
 * this module. Historically each provider eagerly constructed one section object per backend
 * table the moment the provider itself was constructed - and since constructing a section
 * loaded its entire contents into memory, connecting to a database cost time and heap
 * proportional to <em>everything stored in it</em>, whether or not the application would ever
 * touch it (in one real deployment, enough to boot-loop with an OutOfMemoryError). This class
 * makes provider construction O(number of sections) instead: discovery only records section
 * <em>names</em>, and section objects are constructed - and, for {@link CacheMode#FULL},
 * warmed - on first demand.
 * <p>
 * A backend provider only supplies its three storage-side operations
 * ({@link #discoverNames}, {@link #constructSection}, {@link #dropSectionRemote}) plus its own
 * {@code shutdown()}; everything else - memoization, per-section {@link SectionConfig}
 * bookkeeping, replace-on-reconfiguration, the {@link DatabaseProvider} section contract - is
 * implemented here once.
 */
public abstract class AbstractLazyDatabaseProvider implements DatabaseProvider {

    /**
     * Every section name currently known to exist: the names found by the last
     * {@link #discoverNames} pass plus every name created through {@link #createSection} since.
     * This set - not the instance map below - is what {@link #existsSection} and enumeration
     * consult, which is precisely what lets a section <em>exist</em> without ever having been
     * constructed.
     */
    private final Set<String> discoveredNames = ConcurrentHashMap.newKeySet();

    /**
     * The section instances constructed so far, keyed by name - a memo, not the source of
     * truth: a discovered name absent from this map is simply a section nobody asked for yet.
     */
    private final Map<String, AbstractCachedDatabaseSection> sections = Maps.newConcurrentMap();

    /**
     * The {@link SectionConfig} most recently declared per section name via
     * {@link #createSection(String, SectionConfig)}. Kept separately from the instances so a
     * section materialized later - even after a {@link #reload()} dropped its instance - comes
     * back under the configuration its creator declared rather than silently reverting to
     * {@link SectionConfig#full()}.
     */
    private final Map<String, SectionConfig> sectionConfigs = Maps.newConcurrentMap();

    /**
     * Streams the name of every section currently present in the backing store to
     * {@code consumer} - the backend's table/collection/directory/key listing, and nothing
     * more: implementations must not construct section objects or read row data here, since
     * this runs on every {@link #reload()} and at provider construction, exactly the moments
     * the historical row loading made ruinously expensive.
     *
     * @param consumer called once per discovered name (duplicates are tolerated and collapse
     *                 into the name set)
     */
    protected abstract void discoverNames(@NotNull Consumer<String> consumer);

    /**
     * Constructs the backend's section object for {@code name} under {@code config}. Creation
     * of the backend-side container (a {@code CREATE TABLE IF NOT EXISTS}, a directory, a file)
     * belongs in the section's constructor as before, but the constructor must not load row
     * data - the engine ({@link AbstractCachedDatabaseSection}) decides if and when rows are
     * read, and this provider triggers the {@link CacheMode#FULL} warm-up itself where the
     * contract requires it.
     *
     * @param name   the section's name
     * @param config how the section should hold entries in memory
     * @return the constructed, not-yet-warmed section
     */
    protected abstract AbstractCachedDatabaseSection constructSection(@NotNull String name, @NotNull SectionConfig config);

    /**
     * Removes the section's backing container itself from the store (a {@code DROP TABLE}, a
     * directory delete, a key-prefix wipe ...), for {@link #deleteSection}. Storage only - the
     * local bookkeeping is this class' job.
     *
     * @param name the section whose backing container to remove
     */
    protected abstract void dropSectionRemote(@NotNull String name);

    /**
     * {@inheritDoc}
     * <p>
     * Rebuilds only the <em>name</em> set from the backing store and drops every memoized
     * section instance; no section objects are constructed and no row data is read - the cost
     * of a reload is one backend listing, regardless of how much data the store holds.
     * Previously obtained section instances are unaffected (they keep serving their own,
     * now-detached state, exactly as this method's contract has always promised); re-fetching a
     * section via {@link #getSection} materializes a fresh instance reflecting the store.
     */
    @Override
    public void reload() {

        this.sections.clear();
        this.discoveredNames.clear();
        this.discoverNames(this.discoveredNames::add);

    }

    /**
     * {@inheritDoc}
     * <p>
     * Uses the {@link SectionConfig} last declared for {@code name} through
     * {@link #createSection(String, SectionConfig)}, or {@link SectionConfig#full()} - the
     * historical warm-at-creation behavior - when none ever was.
     */
    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.createSection(name, this.sectionConfigs.getOrDefault(name, SectionConfig.full()));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Records {@code config} as {@code name}'s configuration, constructs the section if it was
     * never materialized (or replaces the instance if it currently runs under a
     * <em>different</em> configuration - the newest declaration wins, and the replaced
     * instance's cache state is dropped with it), and, for {@link CacheMode#FULL}, warms the
     * returned section synchronously so it is loaded by the time this returns - the timing
     * every pre-existing consumer of {@link #createSection(String)} was built against.
     */
    @Override
    public DatabaseSection createSection(@NotNull String name, @NotNull SectionConfig config) {

        this.sectionConfigs.put(name, config);
        this.discoveredNames.add(name);

        final AbstractCachedDatabaseSection section = this.sections.compute(name, (key, existing) -> {
            if (existing != null && existing.getConfig().equals(config)) return existing;
            return this.constructSection(key, config);
        });

        section.warmUp();
        return section;

    }

    @Override
    public void deleteSection(@NotNull String name) {

        this.dropSectionRemote(name);

        this.sections.remove(name);
        this.discoveredNames.remove(name);
        this.sectionConfigs.remove(name);

    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.discoveredNames.contains(name);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Materializes an instance for every discovered name first - but materialization is cheap
     * by design (no row data is read; even a {@link CacheMode#FULL} section obtained this way
     * loads only on its first data access), so enumerating a database's sections no longer
     * implies loading the database.
     */
    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {

        for (final String name : this.discoveredNames) this.materialize(name);
        return List.copyOf(this.sections.values());

    }

    @Override
    public Optional<DatabaseSection> getSection(@NotNull String name) {

        final AbstractCachedDatabaseSection existing = this.sections.get(name);
        if (existing != null) return Optional.of(existing);

        if (!this.discoveredNames.contains(name)) return Optional.empty();
        return Optional.of(this.materialize(name));

    }

    /**
     * {@inheritDoc}
     * <p>
     * Clears every discovered section's backing store - materializing cold instances as
     * needed, which stays cheap since clearing never requires loading - and then forgets all
     * local section state, matching the historical behavior where a cleared provider reports
     * no sections until they are re-created or re-discovered via {@link #reload()}.
     */
    @Override
    public void clear() {

        for (final DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.forgetSections();

    }

    /**
     * Drops every piece of local section bookkeeping (instances, names, configurations)
     * without touching the backing store - the local half of {@link #clear()}, also used by
     * subclasses' {@code shutdown()} implementations.
     */
    protected final void forgetSections() {

        this.sections.clear();
        this.discoveredNames.clear();
        this.sectionConfigs.clear();

    }

    /**
     * Returns {@code name}'s memoized section instance, constructing it under its declared (or
     * default {@link SectionConfig#full()}) configuration if this is the first demand for it.
     * Deliberately does <em>not</em> warm the result: the warm-at-creation contract belongs to
     * {@link #createSection} alone, and a {@link CacheMode#FULL} section materialized here
     * loads itself on first data access instead.
     *
     * @param name the section to materialize
     * @return the memoized or freshly constructed section
     */
    private AbstractCachedDatabaseSection materialize(@NotNull String name) {
        return this.sections.computeIfAbsent(name, key -> this.constructSection(key, this.sectionConfigs.getOrDefault(key, SectionConfig.full())));
    }

}
