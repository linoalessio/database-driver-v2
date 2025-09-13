package de.lino.database.json.parser;

/*
 * MIT License
 *
 * Copyright (c) lino, 08.09.2025
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

import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

public final class DocumentJsonParser {

    private DocumentJsonParser() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    public static JsonElement parseString(String json) throws JsonSyntaxException {
        return parseReader(new StringReader(json));
    }

    @NotNull
    public static JsonElement parseReader(Reader reader) throws JsonSyntaxException {

        try {

            final JsonReader jsonReader = new JsonReader(reader);
            Throwable throwable = null;
            JsonElement jsonElement;

            try {

                final JsonElement element = parseReader(jsonReader);
                if (!element.isJsonNull() && jsonReader.peek() != JsonToken.END_DOCUMENT) {
                    throw new JsonSyntaxException("Did not consume the entire document.");
                }

                jsonElement = element;
            } catch (final Throwable exception) {
                throwable = exception;
                throw exception;
            } finally {

                if (jsonReader != null) {

                    if (throwable != null) {

                        try {
                            jsonReader.close();
                        } catch (final Throwable exception) {
                            throwable.addSuppressed(exception);
                        }

                    } else {
                        jsonReader.close();
                    }
                }

            }

            return jsonElement;
        } catch (NumberFormatException | MalformedJsonException exception) {
            throw new JsonSyntaxException(exception);
        } catch (IOException exception) {
            throw new JsonIOException(exception);
        }
    }

    @NotNull
    private static JsonElement parseReader(JsonReader reader) throws JsonIOException, JsonSyntaxException {
        boolean lenient = reader.isLenient();
        reader.setLenient(true);

        JsonElement element;
        try {
            element = Streams.parse(reader);
        } catch (OutOfMemoryError | StackOverflowError exception) {
            throw new JsonParseException("Failed parsing JSON source: " + reader + " to Json", exception);
        } finally {
            reader.setLenient(lenient);
        }

        return element;
    }

}

