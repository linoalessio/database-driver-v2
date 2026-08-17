package de.lino.database.database.factory;

import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.entity.Serialized;
import de.lino.database.json.JsonDocument;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link EntityFactory} implementation: entities are grouped by an arbitrary {@code
 * enum} constant rather than a fixed, closed type, held in an in-memory {@link Cache} obtained
 * through {@link Caches}, and - when constructed with a {@link DatabaseProvider} - additionally
 * persistable through it. Every method is routed per call to either backend via {@link
 * FactoryType}.
 * <p>
 * The {@link FactoryType#DATABASE} path stores each entity's concrete class alongside it (see
 * {@link #toGenericEntry(Serialized)}) so it can be reconstructed generically on read, without
 * callers supplying a {@link Class} token.
 *
 * @see EntityFactory
 * @see FactoryType
 */
public class DefaultEntityFactory implements EntityFactory {

    /**
     * Registered entities, grouped by {@code type} tag. Each tag lazily gets its own {@link
     * CopyOnWriteArrayList}, created on first write via this {@link Cache}'s loader: entities
     * are looked up far more often than they are registered or removed, and a copy-on-write
     * list lets those lookups proceed without any locking or contention, at the cost of copying
     * the backing array on writes.
     */
    private final Cache<String, List<Serialized>> entities;

    /**
     * The provider entities are persisted through when routed to {@link FactoryType#DATABASE},
     * or {@code null} if this factory was constructed without one.
     */
    @Nullable
    private final DatabaseProvider databaseProvider;

    /**
     * Constructs a cache-only {@link EntityFactory}: every {@link FactoryType#DATABASE}-routed
     * call throws {@link UnsupportedOperationException}.
     */
    public DefaultEntityFactory() {
        this(null);
    }

    /**
     * Constructs an {@link EntityFactory} that can additionally persist through {@code
     * databaseProvider}, or a cache-only one if {@code databaseProvider} is {@code null}.
     *
     * @param databaseProvider the provider to persist entities through, or {@code null} to
     *                          disable {@link FactoryType#DATABASE}-routed calls
     */
    public DefaultEntityFactory(@Nullable final DatabaseProvider databaseProvider) {

        this.databaseProvider = databaseProvider;
        this.entities = Caches.newCache(type -> CompletableFuture.completedFuture(new CopyOnWriteArrayList<>()), null, -1);

    }

    @Override
    public void registerEntities(@NotNull final FactoryType factoryType, @NotNull final Enum<?> type, @NotNull final Serialized... entities) {

        Objects.requireNonNull(factoryType, "@DefaultEntityFactory.registerEntities: factoryType cannot be null");
        Objects.requireNonNull(type, "@DefaultEntityFactory.registerEntities: type cannot be null");
        Objects.requireNonNull(entities, "@DefaultEntityFactory.registerEntities: Entities cannot be null");
        Arrays.stream(entities).forEach(entity -> Objects.requireNonNull(entity, "@DefaultEntityFactory.registerEntities: Entity cannot be null"));

        if (factoryType == FactoryType.CACHE) {
            this.entities.get(type.name()).join().addAll(Arrays.asList(entities));
            return;
        }

        final DatabaseSection section = this.requireDatabase().createSection(type.name());
        final List<CompletableFuture<?>> writes = new ArrayList<>();

        for (final Serialized entity : entities) {

            final DatabaseEntry entry = toGenericEntry(entity);

            writes.add(CompletableFuture.runAsync(() -> {
                if (section.exists(entry.getId())) section.update(entry);
                else section.insert(entry);
            }));

        }

        CompletableFuture.allOf(writes.toArray(CompletableFuture<?>[]::new)).join();

    }

    @Override
    public <T extends Serialized> List<T> unregisterEntities(@NotNull final FactoryType factoryType, @NotNull final Enum<?> type, @NotNull final Serialized... entities) {

        Objects.requireNonNull(factoryType, "@DefaultEntityFactory.unregisterEntities: factoryType cannot be null");
        Objects.requireNonNull(type, "@DefaultEntityFactory.unregisterEntities: type cannot be null");
        Objects.requireNonNull(entities, "@DefaultEntityFactory.unregisterEntities: Entities cannot be null");
        Arrays.stream(entities).forEach(entity -> Objects.requireNonNull(entity, "@DefaultEntityFactory.unregisterEntities: Entity cannot be null"));

        if (factoryType == FactoryType.CACHE) {

            final List<Serialized> registered = this.entities.snapshot().get(type.name());
            if (registered == null) return List.of();

            final List<Serialized> removed = Arrays.stream(entities).filter(registered::contains).toList();
            registered.removeAll(Arrays.asList(entities));

            return uncheckedCast(List.copyOf(removed));

        }

        final DatabaseSection section = this.requireDatabase().createSection(type.name());
        final List<CompletableFuture<Serialized>> deletes = new ArrayList<>();

        for (final Serialized entity : entities) {
            deletes.add(CompletableFuture.supplyAsync(() -> {
                if (!section.exists(entity.primaryKey())) return null;
                section.delete(entity.primaryKey());
                return entity;
            }));
        }

        final List<Serialized> removed = deletes.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        return uncheckedCast(removed);

    }

    @Override
    public <T extends Serialized> Optional<T> findEntity(@NotNull final FactoryType factoryType, @NotNull final Enum<?> type, @NotNull final Object key) {

        Objects.requireNonNull(factoryType, "@DefaultEntityFactory.findEntity: factoryType cannot be null");
        Objects.requireNonNull(type, "@DefaultEntityFactory.findEntity: type cannot be null");
        Objects.requireNonNull(key, "@DefaultEntityFactory.findEntity: key cannot be null");

        if (factoryType == FactoryType.CACHE) {

            final List<Serialized> registered = this.entities.snapshot().get(type.name());
            if (registered == null) return Optional.empty();

            return registered.stream()
                    .filter(entity -> entity.hasKey(key.toString()))
                    .findFirst()
                    .map(DefaultEntityFactory::uncheckedCast);

        }

        final Optional<DatabaseSection> section = this.requireDatabase().getSection(type.name());
        if (section.isEmpty()) return Optional.empty();

        final Optional<DatabaseEntry> byId = section.get().findEntryById(key.toString());
        if (byId.isPresent()) {
            final Serialized loaded = fromGenericEntry(byId.get());
            if (loaded != null) return Optional.of(uncheckedCast(loaded));
        }

        return section.get().getEntries().stream()
                .map(DefaultEntityFactory::fromGenericEntry)
                .filter(Objects::nonNull)
                .filter(entity -> entity.hasKey(key.toString()))
                .findFirst()
                .map(DefaultEntityFactory::uncheckedCast);

    }

    @Override
    public <T extends Serialized> List<T> getEntities(@NotNull final FactoryType factoryType, @NotNull final Enum<?> type) {

        Objects.requireNonNull(factoryType, "@DefaultEntityFactory.getEntities: factoryType cannot be null");
        Objects.requireNonNull(type, "@DefaultEntityFactory.getEntities: type cannot be null");

        if (factoryType == FactoryType.CACHE) {
            final List<Serialized> registered = this.entities.snapshot().get(type.name());
            return registered == null ? List.of() : uncheckedCast(Collections.unmodifiableList(registered));
        }

        final Optional<DatabaseSection> section = this.requireDatabase().getSection(type.name());
        if (section.isEmpty()) return List.of();

        final List<Serialized> loaded = section.get().getEntries().stream()
                .map(DefaultEntityFactory::fromGenericEntry)
                .filter(Objects::nonNull)
                .toList();

        return uncheckedCast(loaded);

    }

    /**
     * Builds the {@link DatabaseEntry} {@link #registerEntities}, {@link #findEntity(FactoryType,
     * Enum, Object)} and {@link #getEntities(FactoryType, Enum)} persist and read {@code entity}
     * as: its JSON state under {@code "data"}, plus its concrete class' fully qualified name
     * under {@code "class"} so {@link #fromGenericEntry(DatabaseEntry)} can reconstruct the exact
     * same type later without a caller-supplied {@link Class} token.
     *
     * @param entity the entity to build a database entry for
     * @return the resulting {@link DatabaseEntry}
     */
    private static DatabaseEntry toGenericEntry(final Serialized entity) {
        return new DatabaseEntry(
                entity.primaryKey(),
                new JsonDocument().append("data", entity).append("class", entity.getClass().getName())
        );
    }

    /**
     * Reconstructs the entity {@link #toGenericEntry(Serialized)} previously persisted as {@code
     * entry}, using the concrete class name stored alongside it.
     *
     * @param entry the database entry to reconstruct an entity from
     * @return the reconstructed entity, or {@code null} if {@code entry} carries no {@code
     *         "class"} field, or that class can no longer be found
     */
    @Nullable
    private static Serialized fromGenericEntry(final DatabaseEntry entry) {

        if (!entry.getDocument().contains("class")) return null;
        final String className = entry.getDocument().getString("class");

        try {
            final Class<? extends Serialized> entityClass = Class.forName(className).asSubclass(Serialized.class);
            return entry.getDocument().get("data", entityClass);
        } catch (final ClassNotFoundException exception) {
            exception.printStackTrace();
            return null;
        }

    }

    /**
     * Returns this factory's {@link DatabaseProvider}, provided it was constructed with one.
     *
     * @return this factory's {@link DatabaseProvider}
     * @throws UnsupportedOperationException if this factory was constructed without a {@link
     *                                        DatabaseProvider}
     */
    private DatabaseProvider requireDatabase() {

        if (this.databaseProvider == null) {
            throw new UnsupportedOperationException(
                    "@DefaultEntityFactory: this factory has no DatabaseProvider; construct it with one to enable "
                            + FactoryType.DATABASE.getName() + "-routed calls"
            );
        }

        return this.databaseProvider;

    }

    /**
     * Casts {@code object} to {@code T}, unchecked.
     * <p>
     * {@link #findEntity(FactoryType, Enum, Object)}, {@link #getEntities(FactoryType, Enum)}
     * and {@link #unregisterEntities} infer {@code T} purely from the caller's own assignment
     * context, e.g. {@code List<Exam> exams = entityFactory.getEntities(FactoryType.CACHE,
     * MyEntityType.EXAMS);} - callers are trusted to only ever request the concrete type
     * actually registered under a given {@code type} tag. Since the JVM erases generic type
     * parameters, that trust cannot be verified here. A mismatched {@code T} surfaces as a
     * {@link ClassCastException} at the caller's own assignment, not inside this class.
     *
     * @param object the value to cast
     * @param <T>    the target type
     * @return {@code object}, cast to {@code T}
     */
    @SuppressWarnings("unchecked")
    private static <T> T uncheckedCast(final Object object) {
        return (T) object;
    }

}
