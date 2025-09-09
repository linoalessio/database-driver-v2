package de.lino.database.json.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import de.lino.database.json.JsonDocument;

import java.io.IOException;

public class JsonDocumentTypeAdapter extends TypeAdapter<JsonDocument> {

    public void write(JsonWriter jsonWriter, JsonDocument jsonConfiguration) throws IOException {
        TypeAdapters.JSON_ELEMENT.write(jsonWriter, jsonConfiguration == null ? new JsonObject() : jsonConfiguration.getJsonObject());
    }

    public JsonDocument read(JsonReader jsonReader) throws IOException {
        JsonElement jsonElement = TypeAdapters.JSON_ELEMENT.read(jsonReader);
        return jsonElement != null && jsonElement.isJsonObject() ? new JsonDocument(jsonElement.getAsJsonObject()) : null;
    }
}
