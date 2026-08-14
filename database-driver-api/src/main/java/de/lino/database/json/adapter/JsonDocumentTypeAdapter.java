package de.lino.database.json.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    public void write(@NotNull JsonWriter jsonWriter, @Nullable JsonDocument jsonConfiguration) throws IOException {
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
    @Nullable
    public JsonDocument read(@NotNull JsonReader jsonReader) throws IOException {
        JsonElement jsonElement = TypeAdapters.JSON_ELEMENT.read(jsonReader);
        return jsonElement != null && jsonElement.isJsonObject() ? new JsonDocument(jsonElement.getAsJsonObject()) : null;
    }
}
