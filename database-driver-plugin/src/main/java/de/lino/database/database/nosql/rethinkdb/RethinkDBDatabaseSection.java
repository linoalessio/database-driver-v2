package de.lino.database.database.nosql.rethinkdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.ast.Db;
import com.rethinkdb.gen.ast.Table;
import com.rethinkdb.model.MapObject;
import com.rethinkdb.net.Connection;
import com.rethinkdb.net.Result;
import com.rethinkdb.utils.Types;
import de.lino.database.database.AbstractCachedDatabaseSection;
import de.lino.database.database.exception.NoSuchDataFound;
import de.lino.database.json.JsonDocument;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@link DatabaseSection} backing one RethinkDB table. All caching lives in
 * {@link AbstractCachedDatabaseSection}; this class only supplies the table's storage
 * primitives - each one a single ReQL term against the {@code {id, values}} row shape.
 */
@Getter
public class RethinkDBDatabaseSection extends AbstractCachedDatabaseSection {

    /**
     * The row shape ({@code {id, values}}) every query against {@link #table} is deserialized as.
     */
    private final TypeReference<Map<String, String>> cache;

    /**
     * The connection shared with this section's owning {@link RethinkDBDatabaseProvider} and
     * every one of its sibling sections.
     */
    private final Connection connection;

    /**
     * The table this section wraps.
     */
    private final Table table;

    /**
     * Loads {@code name}'s existing rows into the inherited in-memory view, via
     * {@link #reload()}.
     *
     * @param name       this section's table name
     * @param connection the connection to run every query through
     * @param db         the database {@code name}'s table belongs to
     */
    public RethinkDBDatabaseSection(@NotNull String name, @NotNull Connection connection, @NotNull Db db) {

        super(name);
        this.connection = connection;
        this.cache = Types.mapOf(String.class, String.class);
        this.table = db.table(name);

        this.reload();

    }

    /**
     * {@inheritDoc}
     * <p>
     * Iterates the table's result cursor, which the driver batches server-side - the table is
     * never materialized as a whole on this side of the wire.
     */
    @Override
    protected void loadAll(@NotNull Consumer<DatabaseEntry> consumer) {

        try (final Result<Map<String, String>> result = this.table.run(this.connection, this.cache)) {

            while (result.hasNext()) {
                consumer.accept(this.readEntry(result.next()));
            }

        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * A primary-key {@code get} - RethinkDB's native point read, keyed on the same {@code id}
     * field every write here stores.
     */
    @Override
    protected Optional<DatabaseEntry> fetchOne(@NotNull String id) {

        final Map<String, String> content = this.pointRead(id);
        return content == null ? Optional.empty() : Optional.of(this.readEntry(content));

    }

    /**
     * Parses one stored row back into a {@link DatabaseEntry}, the shared row shape
     * ({@code {id, values}}) every read here expects.
     *
     * @param content the stored row to parse
     * @return the parsed entry
     * @throws NoSuchDataFound if the row holds no {@code "data"} key - preserved from the
     *                         historical loading code even though rows written by
     *                         {@link #persistInsert} carry {@code "values"}, not {@code "data"};
     *                         changing the check would change which stored rows load at all
     */
    private @NotNull DatabaseEntry readEntry(@NotNull Map<String, String> content) {

        if (!content.containsKey("data")) throw new NoSuchDataFound(content.get("id"));

        return new DatabaseEntry(Objects.requireNonNull(content).get("id"), new JsonDocument(content.get("values")));

    }

    /**
     * Runs the primary-key {@code get} shared by {@link #fetchOne} and {@link #existsRemote},
     * unwrapping RethinkDB's "single atom, possibly {@code null}" result shape once.
     *
     * @param id the primary key to read
     * @return the stored row, or {@code null} if the table holds none under {@code id}
     */
    private @Nullable Map<String, String> pointRead(@NotNull String id) {

        try (final Result<Map<String, String>> result = this.table.get(id).run(this.connection, this.cache)) {
            return result.hasNext() ? result.next() : null;
        }

    }

    @Override
    protected void persistInsert(@NotNull DatabaseEntry databaseEntry) {
        this.table.insert(this.mapping(databaseEntry)).runNoReply(this.connection);
    }

    @Override
    protected void persistUpdate(@NotNull DatabaseEntry databaseEntry) {
        this.table.update(this.mapping(databaseEntry)).runNoReply(this.connection);
    }

    @Override
    protected void persistDelete(@NotNull String id) {
        this.table.filter(this.mapping(id)).delete().runNoReply(this.connection);
    }

    @Override
    protected long countRemote() {

        try (final Result<Long> result = this.table.count().run(this.connection, Long.class)) {
            return result.hasNext() ? result.next() : 0L;
        }

    }

    @Override
    protected boolean existsRemote(@NotNull String id) {
        return this.pointRead(id) != null;
    }

    @Override
    protected void clearRemote() {
        this.table.delete().runNoReply(this.connection);
    }

    private MapObject<Object, Object> mapping(@NotNull String id) {
        return RethinkDB.r.hashMap("id", id);
    }

    private Map<Object, Object> mapping(@NotNull DatabaseEntry databaseEntry) {
        return this.mapping(databaseEntry.getId()).with("values", databaseEntry.getDocument().toString());
    }

}
