package de.lino.database.exception;

import org.jetbrains.annotations.NotNull;

/*
 * MIT License
 *
 * Copyright (c) lino, 14.09.2025
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

/**
 * Thrown when an operation (such as {@code update} or {@code delete}) is attempted on a
 * {@link de.lino.database.provider.entity.DatabaseEntry} whose id does not exist in the target
 * {@link de.lino.database.provider.DatabaseSection}.
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
