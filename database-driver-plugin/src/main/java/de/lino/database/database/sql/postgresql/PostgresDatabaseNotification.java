package de.lino.database.database.sql.postgresql;

import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.Serialized;
import de.lino.database.database.notification.DatabaseNotification;
import de.lino.database.database.sql.SQLExecution;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Blocks a dedicated daemon thread on Postgres {@code LISTEN}/{@code NOTIFY} so a caller
 * learns about a row written to a watched table the instant it happens - true push, not a
 * poll loop on a timer. The Postgres implementation of {@link DatabaseNotification}; there is
 * no vendor-agnostic implementation of that contract because {@code LISTEN}/{@code NOTIFY} has
 * no equivalent {@code database-driver-api} exposes today - that module's {@code DatabaseType}
 * (checked against its {@code 1.3.11} sources) exposes pure CRUD only, with no watch/subscribe
 * primitive, so there is nothing vendor-neutral to build this on.
 *
 * <p><b>Two connections, for two different reasons.</b> {@link #watch} - trigger installation,
 * a plain call-and-return DDL statement - runs through {@link SQLExecution}, the same pooled
 * (HikariCP) connection helper {@code SQLDatabaseProvider}/{@code SQLDatabaseSection} themselves
 * run every query and update through, so it reuses this codebase's existing SQL infrastructure
 * instead of opening yet another ad-hoc connection for it. {@code LISTEN}/{@code NOTIFY}
 * cannot go through it, though: {@link #start} needs to hold one specific connection open and
 * block a read on it indefinitely, and {@link SQLExecution} has no API for that - every method
 * it exposes borrows a connection from its pool and returns it before the call is done, which
 * is exactly wrong for a session that must stay open and become the target of a later {@code
 * pg_notify}. So {@link #start} instead opens its own single, dedicated raw JDBC {@link
 * Connection} (not pooled, not shared with {@link SQLExecution}) and issues {@code LISTEN} on
 * it directly - the only place in this class, and in this codebase, that bypasses {@code
 * database-driver-api} entirely.
 *
 * <p><b>This only catches writes that reach the watched table as a real SQL {@code INSERT}/
 * {@code UPDATE}</b> - which every writer talking to that table causes, regardless of process,
 * satisfying "notify me about writes from any source", not just this JVM's own {@code
 * DataFactory} calls.
 *
 * <p><b>Fragile by construction - read before relying on this in production.</b> The trigger
 * installed by {@link #watch} assumes the exact Postgres schema {@code database-driver-plugin}
 * {@code 1.3.11}'s {@code SQLDatabaseSection} creates for every section/table: {@code CREATE
 * TABLE ... (id TEXT, data BYTEA)}, table name = the entity type's {@link Class#getSimpleName()}.
 * That schema is an implementation detail of an external, versioned artifact this repo does not
 * control - it is not a published contract, and a future {@code database-driver-plugin} version
 * could rename or restructure it without notice, silently breaking {@link #watch}. Re-verify this
 * class's assumptions against that module's sources whenever its pinned version changes.
 *
 * <p><b>Usage.</b> {@link #watch} must be called once per table, after that table already
 * exists (i.e. after {@code DatabaseProvider#createSection} has run for that entity type - the
 * underlying {@code CREATE TRIGGER} fails otherwise). Because it runs on its own pooled
 * connection via {@link SQLExecution}, it is safe to call {@link #watch} before, after, or
 * concurrently with a running {@link #start} listener - unlike the listener's own {@link
 * Connection}, {@link SQLExecution} is designed for concurrent multi-threaded use. A brand-new
 * entity type first persisted after {@link #start} has already been called is not picked up
 * automatically - call {@link #watch} again for it. {@link SQLExecution#executeUpdate} logs a
 * failed statement (e.g. a table that does not exist yet) rather than throwing - the same
 * convention every other {@code SQLExecution}-based class in {@code database-driver-plugin}
 * follows - so a mistaken {@link #watch} call before the table exists fails silently to
 * standard error, not with an exception here.
 *
 * <p><b>{@link #watch} installs its three DDL statements as one transaction</b> via
 * {@link SQLExecution#executeTransaction}, not three independent {@link SQLExecution#executeUpdate}
 * calls - the function, the trigger drop, and the trigger create either all apply or none do, so
 * a mid-sequence failure can never leave a table with a half-updated or missing trigger.
 *
 * <p><b>{@link #start}/{@link #shutdown} are mutually exclusive</b>, guarded by
 * {@link #lifecycleLock}: without it, two threads racing {@link #start} could each pass the
 * "already running" check before either assigns {@link #listenerThread}, leaking a connection
 * and a thread that {@link #shutdown} would no longer have a reference to.
 */
public final class PostgresDatabaseNotification implements DatabaseNotification {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final SQLExecution sqlExecution;
    private final Credentials credentials;
    private final String channel;

    /**
     * Serializes {@link #start} and {@link #shutdown} against each other and against themselves,
     * so the check-then-act sequence each performs (read the current state, then open/close a
     * connection and assign the fields below) can't interleave across threads.
     */
    private final Object lifecycleLock = new Object();

    private volatile Connection listenConnection;
    private volatile PGConnection pgConnection;

    private volatile boolean running;
    private volatile Thread listenerThread;

    /**
     * @param credentials the Postgres connection details, used both to build {@link #sqlExecution}'s
     *                    own pooled connection and to open {@link #start}'s dedicated {@code LISTEN} connection
     * @param channel     the Postgres notification channel to listen on
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code channel} is not a safe, unquoted SQL identifier
     */
    public PostgresDatabaseNotification(@NotNull final Credentials credentials, @NotNull final String channel) {

        this.sqlExecution = new SQLExecution(DatabaseType.POSTGRES_SQL, credentials);
        this.credentials = Objects.requireNonNull(credentials, "@PostgresDatabaseNotification.init: credentials cannot be null");

        Objects.requireNonNull(channel, "@PostgresDatabaseNotification.init: channel cannot be null");

        if (!SAFE_IDENTIFIER.matcher(channel).matches())
            throw new IllegalArgumentException("@PostgresDatabaseNotification.init: '" + channel + "' is not a safe SQL identifier");

        this.channel = channel;

    }

    /**
     * Installs an idempotent {@code AFTER INSERT OR UPDATE} trigger on each given entity
     * type's table (name = {@link Class#getSimpleName()}) that {@code pg_notify}s this
     * instance's channel with a small JSON payload - {@code {"table": ..., "operation": ...,
     * "id": ...}}, never the row's own (still-encrypted) data - every time a row is written,
     * by any writer. See class Javadoc for when it is safe to call this relative to {@link #start}.
     *
     * @param types the entity types whose tables should start notifying this channel
     * @throws NullPointerException     if {@code types} is {@code null}
     * @throws IllegalArgumentException if a type's simple name is not a safe, unquoted SQL identifier
     */
    @Override
    @SafeVarargs
    public final void watch(@NotNull final Class<? extends Serialized>... types) {

        Objects.requireNonNull(types, "@PostgresDatabaseNotification.watch: types cannot be null");
        for (final Class<? extends Serialized> type : types) this.installTrigger(type.getSimpleName());

    }

    private void installTrigger(final String table) {

        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("@PostgresDatabaseNotification.watch: '" + table + "' is not a safe SQL identifier");
        }

        final String function = this.channel + "_notify_fn_" + table;
        final String trigger = this.channel + "_notify_trg_" + table;

        final String createFunction = """
                CREATE OR REPLACE FUNCTION %1$s() RETURNS trigger AS $body$
                BEGIN
                    PERFORM pg_notify('%2$s', json_build_object('table', TG_TABLE_NAME, 'operation', TG_OP, 'id', NEW.id)::text);
                    RETURN NEW;
                END;
                $body$ LANGUAGE plpgsql;
                """.formatted(function, this.channel);

        final String dropTrigger = "DROP TRIGGER IF EXISTS " + trigger + " ON " + table + ";";

        final String createTrigger = """
                CREATE TRIGGER %1$s
                AFTER INSERT OR UPDATE ON %2$s
                FOR EACH ROW EXECUTE FUNCTION %3$s();
                """.formatted(trigger, table, function);

        try {
            this.sqlExecution.executeTransaction(createFunction, dropTrigger, createTrigger);
        } catch (final SQLException exception) {
            // same log-and-continue convention SQLExecution#executeUpdate uses elsewhere in
            // this codebase - but here the transaction guarantees the table is left with
            // either its old trigger fully intact or its new one fully installed, never a
            // half-applied mix of the two.
            exception.printStackTrace();
        }

    }

    /**
     * Opens this instance's dedicated {@code LISTEN} connection (see class Javadoc for why it
     * cannot be {@link #sqlExecution}) and starts a daemon thread that blocks on {@link
     * PGConnection#getNotifications(int)} with an indefinite timeout - a real blocking socket
     * read, not a sleep-and-poll loop - invoking {@code onNotification} once per notification,
     * in the order received. Calling this again while already running is a no-op; the whole
     * check-and-open sequence runs under {@link #lifecycleLock} so concurrent callers can't
     * race into opening two connections (see class Javadoc).
     *
     * @param onNotification invoked with each notification's payload, parsed as a {@link JsonDocument}
     * @throws NullPointerException if {@code onNotification} is {@code null}
     * @throws RuntimeException     if opening the dedicated connection or issuing {@code LISTEN} fails
     */
    @Override
    public void start(@NotNull final Consumer<JsonDocument> onNotification) {

        Objects.requireNonNull(onNotification, "@PostgresDatabaseNotification.start: onNotification cannot be null");

        synchronized (this.lifecycleLock) {

            if (this.listenerThread != null) return;

            final String url = "jdbc:postgresql://" + this.credentials.getAddress() + ":" + this.credentials.getPort() + "/" + this.credentials.getDatabase();

            try {
                this.listenConnection = DriverManager.getConnection(url, this.credentials.getUserName(), this.credentials.getPassword());
                this.pgConnection = this.listenConnection.unwrap(PGConnection.class);
                try (final Statement statement = this.listenConnection.createStatement()) {
                    statement.execute("LISTEN " + this.channel + ";");
                }
            } catch (final SQLException exception) {
                throw new RuntimeException("@PostgresDatabaseNotification.start: failed to open the LISTEN connection on channel '" + this.channel + "'", exception);
            }

            this.running = true;
            this.listenerThread = new Thread(() -> this.listen(onNotification), this.channel + "-postgres-change-notifier");
            this.listenerThread.setDaemon(true);
            this.listenerThread.start();

        }

    }

    private void listen(final Consumer<JsonDocument> onNotification) {

        while (this.running) {

            try {

                // Blocks the calling thread until at least one notification arrives, or this
                // connection is closed by shutdown() - never busy-waits or sleeps in between.
                final PGNotification[] notifications = this.pgConnection.getNotifications(0);
                if (notifications == null) continue;

                for (final PGNotification notification : notifications)
                    onNotification.accept(new JsonDocument(notification.getParameter()));

            } catch (final SQLException exception) {
                if (!this.running) return; // shutdown() closed the connection on purpose
                exception.printStackTrace();
                return; // connection is broken; no reconnect logic here, see class Javadoc
            }

        }

    }

    /**
     * Stops the listener thread and closes its dedicated {@code LISTEN} connection - which also
     * unblocks its in-progress {@link PGConnection#getNotifications(int)} call - but leaves
     * {@link #sqlExecution} untouched, since this instance does not own its lifecycle. Waits up
     * to 2 seconds for the listener thread to actually exit. A no-op if {@link #start} was
     * never called. Once this returns, {@link #start} can be called again to open a fresh
     * listener.
     */
    @Override
    public void shutdown() {

        synchronized (this.lifecycleLock) {

            this.running = false;

            final Connection connection = this.listenConnection;
            if (connection != null) {
                try {
                    connection.close();
                } catch (final SQLException ignored) {
                    // closing an already-broken connection is not an error condition here
                }
            }

            final Thread thread = this.listenerThread;
            if (thread == null) return;

            try {
                thread.join(Duration.ofSeconds(2).toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }

            this.listenerThread = null;
            this.pgConnection = null;
            this.listenConnection = null;

        }

    }

    /**
     * @return the Postgres notification channel this instance was constructed to listen on
     */
    @Override
    public @NotNull String getChannel() {
        return this.channel;
    }

    /**
     * @return {@code true} between a successful {@link #start} call and the matching {@link #shutdown}
     */
    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * @return the daemon thread {@link #start} spawned to block on {@code LISTEN} notifications
     * @throws IllegalStateException if {@link #start} has not been called yet
     */
    @Override
    public @NotNull Thread getThread() {
        final Thread thread = this.listenerThread;
        if (thread == null) throw new IllegalStateException("@PostgresDatabaseNotification.getThread: start() has not been called yet");
        return thread;
    }

}
