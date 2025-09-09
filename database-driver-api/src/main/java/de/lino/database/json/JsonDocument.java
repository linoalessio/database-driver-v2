package de.lino.database.json;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.reflect.TypeToken;
import de.lino.database.json.file.FileProvider;
import de.lino.database.json.adapter.JsonDocumentTypeAdapter;
import de.lino.database.json.parser.DocumentJsonParser;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Getter
public class JsonDocument {

    public Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .serializeSpecialFloatingPointValues()
        .setDateFormat(DateFormat.LONG)
        .registerTypeAdapterFactory(TypeAdapters.newTypeHierarchyFactory(JsonDocument.class, new JsonDocumentTypeAdapter()))
        .create();
    public JsonObject jsonObject;

    public JsonDocument() {
        this.jsonObject = new JsonObject();
    }

    public JsonDocument(@NotNull byte[] bytes) {
        try (InputStreamReader stream = new InputStreamReader(new ByteArrayInputStream(bytes))) {
            JsonElement element = DocumentJsonParser.parseReader(stream);
            if (!element.isJsonObject()) {
                this.jsonObject = new JsonObject();
                return;
            }

            this.jsonObject = element.getAsJsonObject();
        } catch (final Throwable throwable) {
            throwable.printStackTrace();
            this.jsonObject = new JsonObject();
        }
    }

    @Deprecated
    public JsonDocument(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    public JsonDocument(String json) {
        JsonElement jsonElement;
        try {
            jsonElement = DocumentJsonParser.parseString(json);
        } catch (final Exception ex) {
            jsonElement = new JsonObject();
        }

        this.jsonObject = jsonElement.getAsJsonObject();
    }

    public JsonDocument(InputStream stream) {
        try (InputStreamReader inputStreamReader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement jsonElement;
            try {
                jsonElement = DocumentJsonParser.parseReader(inputStreamReader);
            } catch (final Exception ex) {
                jsonElement = new JsonObject();
            }

            this.jsonObject = jsonElement.getAsJsonObject();
        } catch (final IOException exception) {
            exception.printStackTrace();
        }
    }

    public JsonDocument(Reader json) {
        JsonElement jsonElement;
        try {
            jsonElement = DocumentJsonParser.parseReader(json);
        } catch (final Exception ex) {
            jsonElement = new JsonObject();
        }

        this.jsonObject = jsonElement.getAsJsonObject();
    }

    public JsonDocument(File file) {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            this.jsonObject = new JsonDocument(stream).getJsonObject();
        } catch (final IOException exception) {
            exception.printStackTrace();
            this.jsonObject = new JsonObject();
        }
    }

    @Deprecated
    public JsonDocument(JsonElement jsonElement) {
        this(jsonElement.isJsonObject() ? jsonElement.getAsJsonObject() : new JsonObject());
    }

    public JsonDocument(String key, String value) {
        this();
        this.append(key, value);
    }

    public JsonDocument(String key, Object value) {
        this();
        this.append(key, value);
    }

    public JsonDocument(String key, Boolean value) {
        this();
        this.append(key, value);
    }

    public JsonDocument(String key, Number value) {
        this();
        this.append(key, value);
    }

    public JsonDocument(String key, Character value) {
        this();
        this.append(key, value);
    }

    public JsonDocument(String key, JsonDocument value) {
        this();
        this.append(key, value);
    }

    public JsonDocument append(String key, String value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    public JsonDocument append(String key, Object value) {
        if (value == null) {
            this.append(key, JsonNull.INSTANCE);
            return this;
        }
        this.jsonObject.add(key, gson.toJsonTree(value));
        return this;
    }

    public JsonDocument append(String key, Number value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    public JsonDocument append(String key, Boolean value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    public JsonDocument append(String key, Character value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    public JsonDocument append(String key, JsonDocument value) {
        if (value == null) return this;
        this.jsonObject.add(key, value.getJsonObject());
        return this;
    }

    public JsonDocument append(Map<String, Object> value) {
        if (value == null) return this;
        for (Map.Entry<String, Object> entry : value.entrySet()) this.append(entry.getKey(), entry.getValue());
        return this;
    }

    public JsonDocument append(String key, byte[] value) {
        if (key == null || value == null) return this;
        return this.append(key, Base64.getEncoder().encodeToString(value));
    }

    public JsonDocument append(JsonDocument jsonDocument) {
        if (jsonDocument == null) return this;
        return this.append(jsonDocument.getJsonObject());
    }

    public JsonDocument append(JsonObject jsonObject) {
        if (jsonObject == null) return this;
        for (Map.Entry<String, JsonElement> entry : this.jsonObject.entrySet()) this.jsonObject.add(entry.getKey(), entry.getValue());
        return this;
    }

    public JsonDocument append(InputStream inputStream) {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return append(reader);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return this;
    }

    public JsonDocument append(Reader reader) {
        return append(DocumentJsonParser.parseReader(reader).getAsJsonObject());
    }

    public JsonDocument append(String key, List<String> value) {
        if (value == null) return this;
        JsonArray jsonElements = new JsonArray();
        for (String b : value) jsonElements.add(b);
        this.jsonObject.add(key, jsonElements);
        return this;
    }

    public JsonDocument getMetaData(String key) {
        if (!jsonObject.has(key)) {
            return null;
        }

        JsonElement jsonElement = this.jsonObject.get(key);

        if (jsonElement.isJsonObject()) {
            return new JsonDocument(jsonElement);
        } else {
            return null;
        }
    }

    public Set<JsonDocument> getMetaDatas() {

        final Set<JsonDocument> jsonDocuments = Sets.newCopyOnWriteArraySet();

        for (Map.Entry<String, JsonElement> entry : this.jsonObject.entrySet()) {
            jsonDocuments.add(new JsonDocument(entry.getValue()));
            return jsonDocuments;
        }

        return jsonDocuments;

    }

    public JsonDocument remove(String key) {
        this.jsonObject.remove(key);
        return this;
    }

    public JsonDocument clear() {
        for (String key : this.getKeys()) this.jsonObject.remove(key);
        return this;
    }

    public int getInteger(String key) {
        return this.jsonObject.get(key).getAsInt();
    }

    public double getDouble(String key) {
        return this.jsonObject.get(key).getAsDouble();
    }

    public float getFloat(String key) {
        return this.jsonObject.get(key).getAsFloat();
    }

    public byte getByte(String key) {
        return this.jsonObject.get(key).getAsByte();
    }

    public short getShort(String key) {
        return this.jsonObject.get(key).getAsShort();
    }

    public long getLong(String key) {
        return this.jsonObject.get(key).getAsLong();
    }

    public boolean getBoolean(String key) {
        return this.jsonObject.get(key).getAsBoolean();
    }

    public String getString(String key) {
        return this.jsonObject.get(key).getAsString();
    }

    public char getChar(String key) {
        return this.jsonObject.get(key).getAsCharacter();
    }

    public BigDecimal getBigDecimal(String key) {
        return this.jsonObject.get(key).getAsBigDecimal();
    }

    public BigInteger getBigInteger(String key) {
        return this.jsonObject.get(key).getAsBigInteger();
    }

    public byte[] getBinary(String key) {
        return this.jsonObject.get(key).getAsBigInteger().toByteArray();
    }

    public <T> T get(String key, Class<T> clazz) {
        return this.get(key, gson, clazz);
    }

    public <T> T get(String key, TypeToken<T> type) {
        return this.get(key, gson, type);
    }

    public boolean write(String path) {
        return this.write(Paths.get(path));
    }

    public boolean write(Path path) {
        FileProvider.getInstance().updateFile(path);
        try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(Files.newOutputStream(path), "UTF-8")) {
            gson.toJson(jsonObject, outputStreamWriter);
            return true;
        } catch (IOException exception) {
            exception.printStackTrace();
        }
        return false;
    }

    public boolean write(File backend) {
        return this.write(backend.toPath());
    }

    public boolean contains(String key) {
        return key != null && this.jsonObject.has(key);
    }

    public Set<String> getKeys() {
        final Set<String> keys = Sets.newConcurrentHashSet();
        for (Map.Entry<String, JsonElement> x : this.jsonObject.entrySet()) keys.add(x.getKey());
        return keys;
    }

    public JsonDocument copy() {
        return new JsonDocument(this.jsonObject.deepCopy());
    }

    public Map<String, JsonElement> asMap() {
        Map<String, JsonElement> out = Maps.newConcurrentMap();
        for (Map.Entry<String, JsonElement> stringJsonElementEntry : this.jsonObject.entrySet()) {
            out.put(stringJsonElementEntry.getKey(), stringJsonElementEntry.getValue());
        }

        return out;
    }

    public <T> T get(String key, Gson gson, Class<T> clazz) {
        
        if (key == null || gson == null || clazz == null) return null;
        JsonElement jsonElement = get(key);
        if (jsonElement == null) {
            return null;
        } else {
            return gson.fromJson(jsonElement, clazz);
        }
    }

    public <T> T get(String key, Gson gson, TypeToken<T> type) {
        if (key == null || gson == null || type == null) return null;
        if (!contains(key)) return null;
        JsonElement jsonElement = get(key);
        if (jsonElement == null) {
            return null;
        } else {
            return gson.fromJson(jsonElement, type.getType());
        }
    }

    public <T> T get(String key, Type type, T def) {
        return this.get(key, type, def, value -> true);
    }

    public <T> T get(String key, Type type, T def, Predicate<T> predicate) {

        final JsonElement jsonElement = this.jsonObject.get(key);
        if (jsonElement == null) return def;
        final T result = this.gson.fromJson(jsonElement, type);
        if (predicate.test(result)) return result;

        return def;
    }

    @Deprecated
    public JsonElement get(String key) {
        if (!contains(key)) return null;
        return this.jsonObject.get(key);
    }

    @NotNull
    public String toJson() {
        return this.gson.toJson(this.jsonObject);
    }

    @NotNull
    public byte[] toBytes() {
        return this.toJson().getBytes(StandardCharsets.UTF_8);
    }

    public static JsonDocument load(Path input) {

        try {
            try (final InputStreamReader reader = new InputStreamReader(Files.newInputStream(input), StandardCharsets.UTF_8); final BufferedReader bufferedReader = new BufferedReader(reader)) {
                final JsonObject object = DocumentJsonParser.parseReader(bufferedReader).getAsJsonObject();
                return new JsonDocument(object);
            } catch (final Exception exception) {
                exception.getStackTrace();
            }
            return new JsonDocument();
        } catch (final RuntimeException exception) {
            exception.getStackTrace();
        }

        return new JsonDocument();
    }

}
