package de.lino.database.database.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a stored record is found without the expected {@code "data"} payload while a
 * {@link de.lino.database.database.DatabaseSection} is loading or reading its entries, indicating
 * corrupted or unexpectedly shaped persisted data.
 */
public class NoSuchDataFound extends RuntimeException {

    /**
     * Creates a new exception for the record with the given id that is missing its data payload.
     *
     * @param id the primary key of the affected record
     */
    public NoSuchDataFound(@NotNull String id) {
        super("No such data found in document");
    }

}
