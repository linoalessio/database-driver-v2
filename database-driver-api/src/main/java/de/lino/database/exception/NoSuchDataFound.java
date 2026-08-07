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
 * Thrown when a stored record is found without the expected {@code "data"} payload while a
 * {@link de.lino.database.provider.DatabaseSection} is loading or reading its entries, indicating
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
