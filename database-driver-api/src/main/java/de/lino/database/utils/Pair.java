package de.lino.database.utils;

import org.jetbrains.annotations.NotNull;

/**
 * A simple, immutable holder for two related values of possibly different types.
 *
 * @param <T>    the type of the first value
 * @param <R>    the type of the second value
 * @param first  the first value
 * @param second the second value
 */
public record Pair<T, R>(@NotNull T first, @NotNull R second) {

}
