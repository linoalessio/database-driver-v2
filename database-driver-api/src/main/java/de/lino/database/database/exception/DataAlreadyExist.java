package de.lino.database.database.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when an attempt is made to insert a {@link de.lino.database.database.entity.DatabaseEntry}
 * with an id that already exists in the target {@link de.lino.database.database.DatabaseSection}.
 */
public class DataAlreadyExist extends RuntimeException {

    /**
     * Creates a new exception for the entry with the given, already existing, id.
     *
     * @param id the primary key that was already present
     */
    public DataAlreadyExist(@NotNull String id) {
        super("Entry already exists with id='" + id + "'");
    }

}
