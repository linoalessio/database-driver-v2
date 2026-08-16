package de.lino.database.database.entity;

import de.lino.database.json.JsonDocument;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Base class for domain entities that can be persisted through the database-driver
 * plugin and printed to the console.
 *
 * <p>{@link #toString()} renders an instance as a String for console output.
 * {@link #keysOf()} exposes the entity's identifying values, e.g. its primary key
 * and any other unique attributes, in a fixed order, so a driver implementation or
 * calling code can look a specific instance up by one of them without needing extra
 * metadata about the entity's structure.
 *
 * <p>Implementing {@link Serializable} allows instances to be written directly by
 * drivers that fall back to Java serialization. Subclasses must supply their own
 * {@link #toString()} and {@link #equals(Object)} implementations, since neither can
 * be derived generically from {@link #keysOf()}.
 */
public abstract class Serialized implements Serializable {

    /**
     * Explicit serialization version, pinned so future field additions do not
     * invalidate previously serialized instances.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new entity. Reserved for subclasses.
     */
    protected Serialized() {
    }

    /**
     * Returns this entity's identifying values, e.g. its primary key and any other
     * unique attributes such as an email address, in a fixed order. Used to look this
     * entity up by one of those values, see {@link #hasKey(String)}.
     *
     * <p>By convention the first element is this entity's primary key; see
     * {@link #primaryKey()}.
     *
     * @return an ordered list of this entity's identifying values
     */
    public abstract List<String> keysOf();

    /**
     * Returns this entity's primary key.
     *
     * <p>By convention this is the first value returned by {@link #keysOf()}.
     *
     * @return this entity's primary key
     */
    public String primaryKey() {
        return this.keysOf().getFirst();
    }

    /**
     * Checks whether the given value matches one of this entity's identifying
     * values, ignoring case, e.g. whether it is this entity's primary key or one of
     * its other unique attributes.
     *
     * @param key the value to look for, such as a primary key
     * @return {@code true} if {@link #keysOf()} contains a matching value, {@code false} otherwise
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public boolean hasKey(final String key) {

        Objects.requireNonNull(key, "@Serialized.hasKey: key cannot be null");

        return this.keysOf().stream().anyMatch(key::equalsIgnoreCase);

    }

    /**
     * Joins all values returned by {@link #keysOf()} into a single colon-separated
     * string, primarily for compact logging and console output.
     *
     * @return this entity's identifying values joined with {@code ":"}
     */
    public String keysAsString() {
        return String.join(":", this.keysOf());
    }

    /**
     * Serializes this entity to a UTF-8 encoded JSON byte array, wrapping it under a
     * {@code "serialized"} entry so it can be read back by {@link #fromByteArray(byte[], Class)}.
     *
     * @return this entity's serialized form
     */
    public byte[] toByteArray() {
        return new JsonDocument().append("serialized", this).toBytes();
    }

    /**
     * Deserializes an entity previously produced by {@link #toByteArray()} into an instance of
     * the given type.
     *
     * @param bytes the serialized form, as produced by {@link #toByteArray()}
     * @param type  the concrete subclass to deserialize into
     * @param <T>   the concrete subclass to deserialize into
     * @return the deserialized entity
     */
    public static <T extends Serialized> T fromByteArray(final byte[] bytes, final Class<T> type) {
        return new JsonDocument(bytes).get("serialized", type);
    }

}
