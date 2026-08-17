package de.lino.database.database.factory;

import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.entity.Serialized;
import de.lino.database.utils.cache.Cache;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Generic registry of {@link Serialized} entities, grouped by an arbitrary {@code enum}
 * constant rather than a fixed, closed type, and routed per call to either an in-memory
 * {@link Cache} or a {@link DatabaseProvider} via {@link FactoryType}.
 *
 * @see FactoryType
 * @see Serialized
 */
public interface EntityFactory {

    /**
     * Registers one or more entities under {@code type}, routed by {@code factoryType}:
     * {@link FactoryType#CACHE} adds them to the in-memory cache, {@link FactoryType#DATABASE}
     * persists them instead, alongside each entity's concrete class so it can later be
     * reconstructed generically by {@link #findEntity(FactoryType, Enum, Object)} or {@link
     * #getEntities(FactoryType, Enum)}.
     *
     * @param factoryType which backend to register {@code entities} against
     * @param type        the tag to register {@code entities} under, e.g. an application-defined
     *                    {@code enum} constant such as {@code MyEntityType.EXAMS}
     * @param entities    the entities to register
     * @throws NullPointerException          if {@code factoryType}, {@code type}, {@code
     *                                        entities}, or any element of {@code entities} is
     *                                        {@code null}
     * @throws UnsupportedOperationException if {@code factoryType} is {@link
     *                                        FactoryType#DATABASE} and this factory has no
     *                                        {@link DatabaseProvider}
     */
    void registerEntities(@NotNull FactoryType factoryType, @NotNull Enum<?> type, @NotNull Serialized... entities);

    /**
     * Removes one or more previously {@link #registerEntities(FactoryType, Enum, Serialized...)
     * registered} entities, routed by {@code factoryType} the same way {@link
     * #registerEntities(FactoryType, Enum, Serialized...)} is.
     *
     * @param factoryType which backend to unregister {@code entities} from
     * @param type        the tag {@code entities} were registered under
     * @param entities    the entities to unregister
     * @param <T>         the expected concrete type of the removed entities, inferred from the
     *                    caller
     * @return the subset of {@code entities} that was actually registered under {@code type} and
     *         has been removed, in no particular order
     * @throws NullPointerException          if {@code factoryType}, {@code type}, {@code
     *                                        entities}, or any element of {@code entities} is
     *                                        {@code null}
     * @throws UnsupportedOperationException if {@code factoryType} is {@link
     *                                        FactoryType#DATABASE} and this factory has no
     *                                        {@link DatabaseProvider}
     */
    <T extends Serialized> List<T> unregisterEntities(@NotNull FactoryType factoryType, @NotNull Enum<?> type, @NotNull Serialized... entities);

    /**
     * Finds the entity registered under {@code type} whose {@link Serialized#hasKey(String)}
     * matches {@code key}, routed by {@code factoryType}. For {@link FactoryType#DATABASE},
     * every candidate entity is reconstructed generically from its persisted concrete class -
     * see {@link #registerEntities(FactoryType, Enum, Serialized...)} - so no {@link Class}
     * token needs to be supplied here.
     *
     * @param factoryType which backend to search
     * @param type        the tag to search
     * @param key         the key to look for, such as a primary key
     * @param <T>         the expected concrete type of the matching entity, inferred from the
     *                    caller
     * @return the matching entity, or an empty {@link Optional} if none is found
     * @throws NullPointerException          if {@code factoryType}, {@code type}, or {@code key}
     *                                        is {@code null}
     * @throws UnsupportedOperationException if {@code factoryType} is {@link
     *                                        FactoryType#DATABASE} and this factory has no
     *                                        {@link DatabaseProvider}
     */
    <T extends Serialized> Optional<T> findEntity(@NotNull FactoryType factoryType, @NotNull Enum<?> type, @NotNull Object key);

    /**
     * Returns every entity registered under {@code type}, routed by {@code factoryType} the same
     * way {@link #findEntity(FactoryType, Enum, Object)} is.
     *
     * @param factoryType which backend to look up
     * @param type        the tag to look up
     * @param <T>         the expected concrete type of the registered entities, inferred from
     *                    the caller
     * @return the registered entities, or an empty list if none are found
     * @throws NullPointerException          if {@code factoryType} or {@code type} is {@code
     *                                        null}
     * @throws UnsupportedOperationException if {@code factoryType} is {@link
     *                                        FactoryType#DATABASE} and this factory has no
     *                                        {@link DatabaseProvider}
     */
    <T extends Serialized> List<T> getEntities(@NotNull FactoryType factoryType, @NotNull Enum<?> type);

}
