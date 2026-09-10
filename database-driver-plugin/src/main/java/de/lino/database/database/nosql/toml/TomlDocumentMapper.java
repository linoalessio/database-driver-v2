package de.lino.database.database.nosql.toml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between this driver's {@link JsonDocument} model and TOML text, for the TOML file
 * store. The two models do not overlap perfectly, and this mapper resolves every mismatch in
 * one place - so {@link TOMLDatabaseSection}'s storage primitives stay plain file operations -
 * with the following deliberate rules:
 * <ul>
 *   <li><b>{@code null} values are dropped on write.</b> TOML has no {@code null} literal at
 *       all; silently omitting the key (rather than failing the whole write) mirrors how
 *       {@link JsonDocument#append(String, String)} already treats a {@code null} value as
 *       "nothing to store".</li>
 *   <li><b>Integral JSON numbers stay integral.</b> A naive Gson round trip would read every
 *       number back as {@code double} and rewrite {@code age = 23} as {@code age = 23.0};
 *       numbers are therefore converted by inspecting their exact value - integral and
 *       long-ranged becomes a TOML integer, everything else a TOML float.</li>
 *   <li><b>TOML's own restrictions apply to what documents can be stored.</b> Arrays must be
 *       homogeneous and special floating-point values ({@code NaN}, infinities) do not exist
 *       in TOML 0.4; a document violating them fails at write time rather than producing a
 *       file that can never be parsed back.</li>
 * </ul>
 */
final class TomlDocumentMapper {

    private TomlDocumentMapper() {
    }

    /**
     * Renders {@code document} as TOML text, applying the conversion rules above.
     *
     * @param document the document to render
     * @return the TOML representation
     */
    static @NotNull String toToml(@NotNull JsonDocument document) {
        return new TomlWriter().write(toTomlValue(document.getJsonObject()));
    }

    /**
     * Parses TOML text back into a {@link JsonDocument}, the inverse of {@link #toToml}. TOML
     * types map naturally onto JSON ones (tables to objects, arrays to arrays, integers and
     * floats to numbers); the one one-way street is a TOML datetime, which JSON lacks and
     * which comes back as its serialized string form.
     *
     * @param toml the TOML text to parse
     * @return the parsed document
     * @throws IllegalStateException if {@code toml} is not valid TOML (toml4j's parse failure)
     */
    static @NotNull JsonDocument fromToml(@NotNull String toml) {
        return new JsonDocument(new JsonDocument().getGson().toJson(new Toml().read(toml).toMap()));
    }

    /**
     * Recursively converts a Gson tree into the plain-Java shape ({@link Map}/{@link List}/
     * boxed primitives) toml4j's writer expects, enforcing the class rules: {@code null}s are
     * dropped, integral numbers stay {@code long}.
     *
     * @param element the element to convert
     * @return the converted value, or {@code null} for JSON {@code null} (dropped by the
     * object/array branches)
     */
    private static @Nullable Object toTomlValue(@NotNull JsonElement element) {

        if (element.isJsonObject()) {

            final JsonObject object = element.getAsJsonObject();
            final Map<String, Object> map = new LinkedHashMap<>();

            for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
                final Object value = toTomlValue(entry.getValue());
                if (value != null) map.put(entry.getKey(), value);
            }

            return map;

        }

        if (element.isJsonArray()) {

            final JsonArray array = element.getAsJsonArray();
            final List<Object> list = new ArrayList<>(array.size());

            for (final JsonElement item : array) {
                final Object value = toTomlValue(item);
                if (value != null) list.add(value);
            }

            return list;

        }

        if (element.isJsonPrimitive()) {

            final JsonPrimitive primitive = element.getAsJsonPrimitive();

            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isString()) return primitive.getAsString();

            return toTomlNumber(primitive);

        }

        return null; // JsonNull - dropped by the caller, TOML cannot express it

    }

    /**
     * Converts a JSON number to the boxed type that keeps its TOML rendering faithful:
     * anything with an exact integral value inside {@code long} range becomes a TOML integer,
     * everything else a TOML float - see the class documentation for why a plain
     * double-everything conversion is not acceptable.
     *
     * @param primitive the numeric primitive to convert
     * @return the boxed {@link Long} or {@link Double}
     */
    private static @NotNull Object toTomlNumber(@NotNull JsonPrimitive primitive) {

        try {

            final BigDecimal exact = primitive.getAsBigDecimal().stripTrailingZeros();
            if (exact.scale() <= 0) return exact.longValueExact();
            return exact.doubleValue();

        } catch (final ArithmeticException | NumberFormatException outOfIntegerReach) {
            // Beyond long range, or a special value like NaN: hand the double form to the
            // writer - TOML 0.4 cannot express NaN/Infinity, so those fail there, loudly.
            return primitive.getAsDouble();
        }

    }

}
