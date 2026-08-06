package de.lino.database.json;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A lightweight, mutable wrapper around a Gson {@link JsonObject} that provides a fluent,
 * type-safe API for building, reading, converting and persisting JSON documents.
 * <p>
 * Instances can be created from raw bytes, strings, streams, readers, files or single key/value
 * pairs, and populated further through the chained {@code append(...)} methods. Values can be
 * read back either as primitives (via the {@code getXxx} methods) or deserialized into arbitrary
 * types using the {@link Gson} instance embedded in this document.
 */
@Getter
public class JsonDocument {

    /**
     * The {@link Gson} instance used to serialize and deserialize this document's content.
     * Configured with pretty printing, null serialization, disabled HTML escaping, special
     * floating point value support and a {@link JsonDocumentTypeAdapter} for nested
     * {@link JsonDocument} values.
     */
    public Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .serializeSpecialFloatingPointValues()
        .setDateFormat(DateFormat.LONG)
        .registerTypeAdapterFactory(TypeAdapters.newTypeHierarchyFactory(JsonDocument.class, new JsonDocumentTypeAdapter()))
        .create();

    /**
     * The underlying Gson object that backs this document.
     */
    public JsonObject jsonObject;

    /**
     * Creates a new, empty {@link JsonDocument}.
     */
    public JsonDocument() {
        this.jsonObject = new JsonObject();
    }

    /**
     * Parses the given UTF-8 encoded bytes into a {@link JsonDocument}.
     * If the bytes do not represent a JSON object, or parsing fails, an empty document is
     * created instead.
     *
     * @param bytes the raw JSON bytes to parse; must not be {@code null}
     */
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

    /**
     * Wraps an already existing {@link JsonObject} without copying it.
     *
     * @param jsonObject the object to wrap
     * @deprecated exposes the underlying Gson type directly; prefer one of the byte/String/stream
     * based constructors instead
     */
    @Deprecated
    public JsonDocument(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    /**
     * Parses the given JSON string into a {@link JsonDocument}.
     * If parsing fails, an empty document is created instead.
     *
     * @param json the JSON encoded string to parse
     */
    public JsonDocument(String json) {
        JsonElement jsonElement;
        try {
            jsonElement = DocumentJsonParser.parseString(json);
        } catch (final Exception ex) {
            jsonElement = new JsonObject();
        }

        this.jsonObject = jsonElement.getAsJsonObject();
    }

    /**
     * Reads and parses JSON content from the given input stream, decoded as UTF-8.
     * The stream is closed after reading. If parsing fails, an empty document is created instead.
     *
     * @param stream the input stream to read JSON content from
     */
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

    /**
     * Reads and parses JSON content from the given reader.
     * If parsing fails, an empty document is created instead.
     *
     * @param json the reader to read JSON content from
     */
    public JsonDocument(Reader json) {
        JsonElement jsonElement;
        try {
            jsonElement = DocumentJsonParser.parseReader(json);
        } catch (final Exception ex) {
            jsonElement = new JsonObject();
        }

        this.jsonObject = jsonElement.getAsJsonObject();
    }

    /**
     * Reads and parses the given file's content as JSON.
     * If the file cannot be read, an empty document is created instead.
     *
     * @param file the file to load the JSON content from
     */
    public JsonDocument(File file) {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            this.jsonObject = new JsonDocument(stream).getJsonObject();
        } catch (final IOException exception) {
            exception.printStackTrace();
            this.jsonObject = new JsonObject();
        }
    }

    /**
     * Wraps the given {@link JsonElement} if it represents a JSON object, or creates an empty
     * document otherwise.
     *
     * @param jsonElement the element to wrap
     * @deprecated exposes the underlying Gson type directly; prefer one of the byte/String/stream
     * based constructors instead
     */
    @Deprecated
    public JsonDocument(JsonElement jsonElement) {
        this(jsonElement.isJsonObject() ? jsonElement.getAsJsonObject() : new JsonObject());
    }

    /**
     * Creates a new document containing a single string entry.
     *
     * @param key   the entry's key
     * @param value the entry's value
     */
    public JsonDocument(String key, String value) {
        this();
        this.append(key, value);
    }

    /**
     * Creates a new document containing a single entry with an arbitrary value, serialized
     * through this document's {@link Gson} instance.
     *
     * @param key   the entry's key
     * @param value the entry's value
     */
    public JsonDocument(String key, Object value) {
        this();
        this.append(key, value);
    }

    /**
     * Creates a new document containing a single boolean entry.
     *
     * @param key   the entry's key
     * @param value the entry's value
     */
    public JsonDocument(String key, Boolean value) {
        this();
        this.append(key, value);
    }

    /**
     * Creates a new document containing a single numeric entry.
     *
     * @param key   the entry's key
     * @param value the entry's value
     */
    public JsonDocument(String key, Number value) {
        this();
        this.append(key, value);
    }

    /**
     * Creates a new document containing a single character entry.
     *
     * @param key   the entry's key
     * @param value the entry's value
     */
    public JsonDocument(String key, Character value) {
        this();
        this.append(key, value);
    }

    /**
     * Creates a new document containing a single nested-document entry.
     *
     * @param key   the entry's key
     * @param value the nested document to store
     */
    public JsonDocument(String key, JsonDocument value) {
        this();
        this.append(key, value);
    }

    /**
     * Adds or overwrites a string entry.
     *
     * @param key   the entry's key
     * @param value the value to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, String value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    /**
     * Adds or overwrites an entry with an arbitrary value, serialized through this document's
     * {@link Gson} instance. A {@code null} value is stored as {@link JsonNull#INSTANCE}.
     *
     * @param key   the entry's key
     * @param value the value to serialize and store
     * @return this document, for chaining
     */
    public JsonDocument append(String key, Object value) {
        if (value == null) {
            this.append(key, JsonNull.INSTANCE);
            return this;
        }
        this.jsonObject.add(key, gson.toJsonTree(value));
        return this;
    }

    /**
     * Adds or overwrites a numeric entry.
     *
     * @param key   the entry's key
     * @param value the value to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, Number value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    /**
     * Adds or overwrites a boolean entry.
     *
     * @param key   the entry's key
     * @param value the value to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, Boolean value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    /**
     * Adds or overwrites a character entry.
     *
     * @param key   the entry's key
     * @param value the value to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, Character value) {
        if (value == null) return this;
        this.jsonObject.addProperty(key, value);
        return this;
    }

    /**
     * Adds or overwrites an entry whose value is another {@link JsonDocument}.
     *
     * @param key   the entry's key
     * @param value the nested document to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, JsonDocument value) {
        if (value == null) return this;
        this.jsonObject.add(key, value.getJsonObject());
        return this;
    }

    /**
     * Adds every entry of the given map to this document, delegating to
     * {@link #append(String, Object)} for each value.
     *
     * @param value the entries to add; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(Map<String, Object> value) {
        if (value == null) return this;
        for (Map.Entry<String, Object> entry : value.entrySet()) this.append(entry.getKey(), entry.getValue());
        return this;
    }

    /**
     * Adds or overwrites a binary entry, encoded as a Base64 string.
     *
     * @param key   the entry's key
     * @param value the bytes to encode and store; if either argument is {@code null} the call is
     *              a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, byte[] value) {
        if (key == null || value == null) return this;
        return this.append(key, Base64.getEncoder().encodeToString(value));
    }

    /**
     * Merges all entries of another document into this one.
     *
     * @param jsonDocument the document whose entries should be merged in; if {@code null} the
     *                     call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(JsonDocument jsonDocument) {
        if (jsonDocument == null) return this;
        return this.append(jsonDocument.getJsonObject());
    }

    /**
     * Merges all entries of the given {@link JsonObject} into this document.
     *
     * @param jsonObject the object whose entries should be merged in; if {@code null} the call is
     *                   a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(JsonObject jsonObject) {
        if (jsonObject == null) return this;
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) this.jsonObject.add(entry.getKey(), entry.getValue());
        return this;
    }

    /**
     * Reads JSON content from the given input stream, decoded as UTF-8, and merges it into this
     * document. The stream is closed after reading.
     *
     * @param inputStream the stream to read JSON content from
     * @return this document, for chaining
     */
    public JsonDocument append(InputStream inputStream) {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return append(reader);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return this;
    }

    /**
     * Reads JSON content from the given reader and merges it into this document.
     *
     * @param reader the reader to read JSON content from
     * @return this document, for chaining
     * @throws JsonSyntaxException if the reader's content is not valid JSON
     */
    public JsonDocument append(Reader reader) {
        return append(DocumentJsonParser.parseReader(reader).getAsJsonObject());
    }

    /**
     * Adds or overwrites an entry holding a JSON array of strings.
     *
     * @param key   the entry's key
     * @param value the strings to store; if {@code null} the call is a no-op
     * @return this document, for chaining
     */
    public JsonDocument append(String key, List<String> value) {
        if (value == null) return this;
        JsonArray jsonElements = new JsonArray();
        for (String b : value) jsonElements.add(b);
        this.jsonObject.add(key, jsonElements);
        return this;
    }

    /**
     * Returns the nested document stored under the given key.
     *
     * @param key the key to look up
     * @return a new {@link JsonDocument} wrapping the nested object, or {@code null} if the key
     * is absent or does not hold a JSON object
     */
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

    /**
     * Returns the value of every top-level entry of this document as a {@link JsonDocument}.
     *
     * @return a set containing one {@link JsonDocument} per entry of this document
     */
    public Set<JsonDocument> getMetaDatas() {

        final Set<JsonDocument> jsonDocuments = new HashSet<>();

        for (Map.Entry<String, JsonElement> entry : this.jsonObject.entrySet()) {
            jsonDocuments.add(new JsonDocument(entry.getValue()));
        }

        return jsonDocuments;

    }

    /**
     * Removes the entry with the given key, if present.
     *
     * @param key the key to remove
     * @return this document, for chaining
     */
    public JsonDocument remove(String key) {
        this.jsonObject.remove(key);
        return this;
    }

    /**
     * Removes all entries from this document.
     *
     * @return this document, for chaining
     */
    public JsonDocument clear() {
        for (String key : this.getKeys()) this.jsonObject.remove(key);
        return this;
    }

    /**
     * Returns the value of the given key as an {@code int}.
     *
     * @param key the key to look up
     * @return the value as an {@code int}
     * @throws NullPointerException if the key does not exist
     */
    public int getInteger(String key) {
        return this.jsonObject.get(key).getAsInt();
    }

    /**
     * Returns the value of the given key as a {@code double}.
     *
     * @param key the key to look up
     * @return the value as a {@code double}
     * @throws NullPointerException if the key does not exist
     */
    public double getDouble(String key) {
        return this.jsonObject.get(key).getAsDouble();
    }

    /**
     * Returns the value of the given key as a {@code float}.
     *
     * @param key the key to look up
     * @return the value as a {@code float}
     * @throws NullPointerException if the key does not exist
     */
    public float getFloat(String key) {
        return this.jsonObject.get(key).getAsFloat();
    }

    /**
     * Returns the value of the given key as a {@code byte}.
     *
     * @param key the key to look up
     * @return the value as a {@code byte}
     * @throws NullPointerException if the key does not exist
     */
    public byte getByte(String key) {
        return this.jsonObject.get(key).getAsByte();
    }

    /**
     * Returns the value of the given key as a {@code short}.
     *
     * @param key the key to look up
     * @return the value as a {@code short}
     * @throws NullPointerException if the key does not exist
     */
    public short getShort(String key) {
        return this.jsonObject.get(key).getAsShort();
    }

    /**
     * Returns the value of the given key as a {@code long}.
     *
     * @param key the key to look up
     * @return the value as a {@code long}
     * @throws NullPointerException if the key does not exist
     */
    public long getLong(String key) {
        return this.jsonObject.get(key).getAsLong();
    }

    /**
     * Returns the value of the given key as a {@code boolean}.
     *
     * @param key the key to look up
     * @return the value as a {@code boolean}
     * @throws NullPointerException if the key does not exist
     */
    public boolean getBoolean(String key) {
        return this.jsonObject.get(key).getAsBoolean();
    }

    /**
     * Returns the value of the given key as a {@link String}.
     *
     * @param key the key to look up
     * @return the value as a {@link String}
     * @throws NullPointerException if the key does not exist
     */
    public String getString(String key) {
        return this.jsonObject.get(key).getAsString();
    }

    /**
     * Returns the value of the given key as a {@code char}.
     *
     * @param key the key to look up
     * @return the value as a {@code char}
     * @throws NullPointerException if the key does not exist
     */
    public char getChar(String key) {
        return this.jsonObject.get(key).getAsCharacter();
    }

    /**
     * Returns the value of the given key as a {@link BigDecimal}.
     *
     * @param key the key to look up
     * @return the value as a {@link BigDecimal}
     * @throws NullPointerException if the key does not exist
     */
    public BigDecimal getBigDecimal(String key) {
        return this.jsonObject.get(key).getAsBigDecimal();
    }

    /**
     * Returns the value of the given key as a {@link BigInteger}.
     *
     * @param key the key to look up
     * @return the value as a {@link BigInteger}
     * @throws NullPointerException if the key does not exist
     */
    public BigInteger getBigInteger(String key) {
        return this.jsonObject.get(key).getAsBigInteger();
    }

    /**
     * Returns the value of the given key as raw binary data, interpreted as a {@link BigInteger}
     * and converted to its byte representation.
     *
     * @param key the key to look up
     * @return the decoded bytes
     * @throws NullPointerException if the key does not exist
     */
    public byte[] getBinary(String key) {
        return this.jsonObject.get(key).getAsBigInteger().toByteArray();
    }

    /**
     * Deserializes the value of the given key into an instance of the given class, using this
     * document's {@link Gson} instance.
     *
     * @param key   the key to look up
     * @param clazz the target type
     * @param <T>   the target type
     * @return the deserialized value, or {@code null} if the key does not exist
     */
    public <T> T get(String key, Class<T> clazz) {
        return this.get(key, gson, clazz);
    }

    /**
     * Deserializes the value of the given key into the given generic type, using this document's
     * {@link Gson} instance.
     *
     * @param key  the key to look up
     * @param type the target type
     * @param <T>  the target type
     * @return the deserialized value, or {@code null} if the key does not exist
     */
    public <T> T get(String key, TypeToken<T> type) {
        return this.get(key, gson, type);
    }

    /**
     * Writes this document's content as pretty-printed JSON to the file at the given path.
     *
     * @param path the destination file path
     * @return {@code true} if the write succeeded, {@code false} otherwise
     */
    public boolean write(String path) {
        return this.write(Paths.get(path));
    }

    /**
     * Writes this document's content as pretty-printed JSON to the given path.
     * Any existing file at that location is recreated first.
     *
     * @param path the destination path
     * @return {@code true} if the write succeeded, {@code false} otherwise
     */
    public boolean write(Path path) {
        FileProvider.getInstance().updateFile(path);
        try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            gson.toJson(jsonObject, outputStreamWriter);
            return true;
        } catch (IOException exception) {
            exception.printStackTrace();
        }
        return false;
    }

    /**
     * Writes this document's content as pretty-printed JSON to the given file.
     *
     * @param backend the destination file
     * @return {@code true} if the write succeeded, {@code false} otherwise
     */
    public boolean write(File backend) {
        return this.write(backend.toPath());
    }

    /**
     * Checks whether this document has an entry with the given key.
     *
     * @param key the key to check
     * @return {@code true} if the key is present, {@code false} if it is {@code null} or absent
     */
    public boolean contains(String key) {
        return key != null && this.jsonObject.has(key);
    }

    /**
     * Returns the keys of all top-level entries of this document.
     *
     * @return a new set containing every key of this document
     */
    public Set<String> getKeys() {
        final Set<String> keys = new HashSet<>();
        for (Map.Entry<String, JsonElement> x : this.jsonObject.entrySet()) keys.add(x.getKey());
        return keys;
    }

    /**
     * Creates a deep copy of this document.
     *
     * @return a new {@link JsonDocument} with an independent copy of the underlying JSON object
     */
    public JsonDocument copy() {
        return new JsonDocument(this.jsonObject.deepCopy());
    }

    /**
     * Returns a snapshot of this document's top-level entries as a plain map.
     *
     * @return a new map containing every key/value pair of this document
     */
    public Map<String, JsonElement> asMap() {
        Map<String, JsonElement> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> stringJsonElementEntry : this.jsonObject.entrySet()) {
            out.put(stringJsonElementEntry.getKey(), stringJsonElementEntry.getValue());
        }

        return out;
    }

    /**
     * Deserializes the value of the given key into an instance of the given class, using the
     * supplied {@link Gson} instance.
     *
     * @param key   the key to look up
     * @param gson  the Gson instance to deserialize with
     * @param clazz the target type
     * @param <T>   the target type
     * @return the deserialized value, or {@code null} if any argument is {@code null} or the key
     * does not exist
     */
    public <T> T get(String key, Gson gson, Class<T> clazz) {

        if (key == null || gson == null || clazz == null) return null;
        JsonElement jsonElement = get(key);
        if (jsonElement == null) {
            return null;
        } else {
            return gson.fromJson(jsonElement, clazz);
        }
    }

    /**
     * Deserializes the value of the given key into the given generic type, using the supplied
     * {@link Gson} instance.
     *
     * @param key  the key to look up
     * @param gson the Gson instance to deserialize with
     * @param type the target type
     * @param <T>  the target type
     * @return the deserialized value, or {@code null} if any argument is {@code null} or the key
     * does not exist
     */
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

    /**
     * Deserializes the value of the given key into the given type, falling back to a default
     * value if the key is absent.
     *
     * @param key  the key to look up
     * @param type the target type
     * @param def  the value to return if the key is absent
     * @param <T>  the target type
     * @return the deserialized value, or {@code def} if the key is absent
     */
    public <T> T get(String key, Type type, T def) {
        return this.get(key, type, def, value -> true);
    }

    /**
     * Deserializes the value of the given key into the given type, falling back to a default
     * value if the key is absent or the deserialized result does not satisfy the given predicate.
     *
     * @param key       the key to look up
     * @param type      the target type
     * @param def       the value to return if the key is absent or the predicate rejects the
     *                  result
     * @param predicate a predicate the deserialized value must satisfy to be returned
     * @param <T>       the target type
     * @return the deserialized value, or {@code def}
     */
    public <T> T get(String key, Type type, T def, Predicate<T> predicate) {

        final JsonElement jsonElement = this.jsonObject.get(key);
        if (jsonElement == null) return def;
        final T result = this.gson.fromJson(jsonElement, type);
        if (predicate.test(result)) return result;

        return def;
    }

    /**
     * Returns the raw {@link JsonElement} stored under the given key.
     *
     * @param key the key to look up
     * @return the raw element, or {@code null} if the key is absent
     * @deprecated exposes the underlying Gson type directly; prefer the typed {@code get}/{@code
     * getXxx} accessors instead
     */
    @Deprecated
    public JsonElement get(String key) {
        if (!contains(key)) return null;
        return this.jsonObject.get(key);
    }

    /**
     * Serializes this document to its pretty-printed JSON string representation.
     *
     * @return the JSON representation of this document
     */
    @NotNull
    public String toJson() {
        return this.gson.toJson(this.jsonObject);
    }

    /**
     * Serializes this document to JSON and encodes it as UTF-8 bytes.
     *
     * @return the UTF-8 encoded JSON representation of this document
     */
    @NotNull
    public byte[] toBytes() {
        return this.toJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Loads and parses a {@link JsonDocument} from the file at the given path.
     * If the file cannot be read or parsed, an empty document is returned instead.
     *
     * @param input the path to read from
     * @return the parsed document, or an empty document if reading or parsing failed
     */
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
