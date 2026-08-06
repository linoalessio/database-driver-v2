package de.lino.database.json.adapter;

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
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import de.lino.database.json.JsonDocument;

import java.io.IOException;

/**
 * Gson {@link TypeAdapter} that (de)serializes {@link JsonDocument} instances by delegating to
 * their underlying {@link JsonObject}, so that nested {@link JsonDocument} fields on arbitrary
 * classes are transparently written as plain JSON objects instead of being serialized as beans.
 * <p>
 * This adapter is registered on {@link JsonDocument#gson} as a type hierarchy factory.
 */
public class JsonDocumentTypeAdapter extends TypeAdapter<JsonDocument> {

    /**
     * Writes the given document's underlying JSON object, or an empty object if the document is
     * {@code null}.
     *
     * @param jsonWriter        the writer to serialize to
     * @param jsonConfiguration the document to serialize, may be {@code null}
     * @throws IOException if an I/O error occurs while writing
     */
    public void write(JsonWriter jsonWriter, JsonDocument jsonConfiguration) throws IOException {
        TypeAdapters.JSON_ELEMENT.write(jsonWriter, jsonConfiguration == null ? new JsonObject() : jsonConfiguration.getJsonObject());
    }

    /**
     * Reads a JSON value and wraps it in a new {@link JsonDocument} if it represents a JSON
     * object.
     *
     * @param jsonReader the reader to deserialize from
     * @return a new {@link JsonDocument} wrapping the read object, or {@code null} if the read
     * value is absent or not a JSON object
     * @throws IOException if an I/O error occurs while reading
     */
    public JsonDocument read(JsonReader jsonReader) throws IOException {
        JsonElement jsonElement = TypeAdapters.JSON_ELEMENT.read(jsonReader);
        return jsonElement != null && jsonElement.isJsonObject() ? new JsonDocument(jsonElement.getAsJsonObject()) : null;
    }
}
