package de.lino.database.database.entity;

import de.lino.database.json.JsonDocument;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single record stored inside a {@link de.lino.database.database.DatabaseSection}, consisting
 * of a unique primary key and its associated {@link JsonDocument} content.
 * <p>
 * By convention, the wrapped document (see the generated {@code getDocument()} accessor) stores
 * its actual payload under the {@code "data"} key, which can be retrieved directly via
 * {@link #getMetaData()}.
 */
@Getter
@RequiredArgsConstructor
public class DatabaseEntry {

    /**
     * The primary key that uniquely identifies this entry within its section.
     */
    @NotNull
    private final String id;

    /**
     * The full document backing this entry, including its {@code "data"} envelope.
     */
    @NotNull
    private final JsonDocument document;

    /**
     * Contains the MetaData of the entry
     * @return JsonDocument
     */
    @Nullable
    public JsonDocument getMetaData() {
        return this.document.getMetaData("data");
    }

}
