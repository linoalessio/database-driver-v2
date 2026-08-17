package de.lino.database.database.factory;

import de.lino.database.database.DatabaseProvider;
import de.lino.database.utils.cache.Cache;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Enumerates the storage backends an {@link EntityFactory} action can target: whether it runs
 * against the in-memory {@link Cache}, or against a {@link DatabaseProvider}. Passed as a
 * per-call routing argument to {@link EntityFactory#registerEntities}, {@link
 * EntityFactory#unregisterEntities}, {@link EntityFactory#findEntity(FactoryType, Enum, Object)}
 * and {@link EntityFactory#getEntities(FactoryType, Enum)}.
 */
@RequiredArgsConstructor
@Getter @ToString
public enum FactoryType {

    /**
     * Entities only ever live in the {@link EntityFactory}'s in-memory {@link Cache}; no
     * persistence methods are supported.
     */
    CACHE("Cache"),

    /**
     * Entities live in the {@link EntityFactory}'s in-memory {@link Cache} and are additionally
     * persisted through a {@link DatabaseProvider}.
     */
    DATABASE("Database"),;

    /**
     * The human-readable name of this storage backend.
     */
    private final String name;

}
