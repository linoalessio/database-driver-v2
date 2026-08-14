package de.lino.database.database.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when an operation (such as {@code update} or {@code delete}) is attempted on a
 * {@link de.lino.database.database.entity.DatabaseEntry} whose id does not exist in the target
 * {@link de.lino.database.database.DatabaseSection}.
 */
public class NoSuchEntryFound extends RuntimeException {

    /**
     * Creates a new exception for the entry with the given, non-existent, id.
     *
     * @param id the primary key that could not be found
     */
    public NoSuchEntryFound(@NotNull String id) {
        super("No such entry found with id='" + id + "'");
    }

}
