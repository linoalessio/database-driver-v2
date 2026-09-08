package de.lino.database.database.nosql.redis;

import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.Serialized;
import de.lino.database.database.notification.DatabaseNotification;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The Redis implementation of {@link DatabaseNotification}, mirroring
 * {@code PostgresDatabaseNotification}'s contract shape on top of Redis Pub/Sub instead of
 * Postgres {@code LISTEN}/{@code NOTIFY}.
 *
 * <p><b>{@link #watch} is a documented no-op.</b> Redis has no trigger concept to install, unlike
 * Postgres where {@code watch} creates an {@code AFTER INSERT OR UPDATE} trigger per table. Instead,
 * {@link RedisDatabaseSection#insert}/{@link RedisDatabaseSection#update} themselves unconditionally
 * {@code PUBLISH} a {@code {"table", "operation", "id"}} notification - the exact same payload
 * shape {@code PostgresDatabaseNotification}'s trigger function emits, so a consumer needs no
 * special-casing between the two backends - on {@link RedisDatabaseSection#CHANGE_NOTIFICATION_CHANNEL}
 * every time a row is written, by any writer sharing that Redis instance. There is nothing left
 * for {@link #watch} to install; this instance only actually observes those publishes once
 * {@link #getChannel()} equals {@link RedisDatabaseSection#CHANGE_NOTIFICATION_CHANNEL} exactly -
 * passing any other channel to the constructor below is valid but will silently never receive
 * anything from {@link RedisDatabaseSection}'s own writes.
 *
 * <p><b>One dedicated, non-pooled connection, one daemon thread</b> - the same shape
 * {@code PostgresDatabaseNotification#start} uses for its raw JDBC {@code LISTEN} connection.
 * {@link Jedis#subscribe(JedisPubSub, String...)} blocks the calling thread for as long as the
 * subscription is active, so it cannot run through a shared {@link redis.clients.jedis.JedisPool}
 * the way {@link RedisDatabaseSection}'s own reads/writes do - a connection borrowed from that
 * pool for the lifetime of a blocking subscribe would never be returned, silently starving every
 * other borrower. {@link #start} instead opens its own single {@link Jedis} connection built
 * directly from {@link #credentials}, independent of any {@link RedisDatabaseProvider}.
 *
 * <p>The callback passed to {@link #start} is wrapped in its own try/catch (log, don't propagate)
 * - an uncaught exception escaping {@link JedisPubSub#onMessage} would otherwise kill the
 * subscription with no reconnect logic, the same failure mode already documented as a risk on the
 * Postgres side.
 *
 * <p><b>{@link #start}/{@link #shutdown} are mutually exclusive</b>, guarded by
 * {@link #lifecycleLock}: without it, two threads racing {@link #start} could each pass the
 * "already running" check before either assigns {@link #listenerThread}, leaking a connection and
 * a thread {@link #shutdown} would no longer have a reference to.
 */
public final class RedisDatabaseNotification implements DatabaseNotification {

    private final Credentials credentials;
    private final String channel;

    /**
     * Serializes {@link #start} and {@link #shutdown} against each other and against themselves,
     * so the check-then-act sequence each performs (read the current state, then open/close a
     * connection and assign the fields below) can't interleave across threads.
     */
    private final Object lifecycleLock = new Object();

    private volatile Jedis subscriberConnection;
    private volatile JedisPubSub pubSub;

    private volatile boolean running;
    private volatile Thread listenerThread;

    /**
     * @param credentials the Redis connection details {@link #start} opens its dedicated
     *                    subscriber connection with
     * @param channel     the Redis Pub/Sub channel to subscribe on; pass
     *                    {@link RedisDatabaseSection#CHANGE_NOTIFICATION_CHANNEL} to actually
     *                    observe {@link RedisDatabaseSection}'s own write notifications - see
     *                    class Javadoc
     * @throws NullPointerException if either argument is {@code null}
     */
    public RedisDatabaseNotification(@NotNull final Credentials credentials, @NotNull final String channel) {
        this.credentials = Objects.requireNonNull(credentials, "@RedisDatabaseNotification.init: credentials cannot be null");
        this.channel = Objects.requireNonNull(channel, "@RedisDatabaseNotification.init: channel cannot be null");
    }

    /**
     * A documented no-op - see class Javadoc for why. Redis has no trigger concept to install
     * per type/table the way {@code PostgresDatabaseNotification#watch} does; every write already
     * publishes unconditionally, regardless of which types are passed here.
     *
     * @param types ignored
     * @throws NullPointerException if {@code types} is {@code null}
     */
    @Override
    @SafeVarargs
    public final void watch(@NotNull final Class<? extends Serialized>... types) {
        Objects.requireNonNull(types, "@RedisDatabaseNotification.watch: types cannot be null");
    }

    /**
     * Opens this instance's dedicated subscriber connection (see class Javadoc for why it cannot
     * be a shared {@link redis.clients.jedis.JedisPool}) and starts a daemon thread that blocks on
     * {@link Jedis#subscribe(JedisPubSub, String...)} - a real blocking socket read, not a
     * sleep-and-poll loop - invoking {@code onNotification} once per message received, in the
     * order received. Calling this again while already running is a no-op; the whole
     * check-and-open sequence runs under {@link #lifecycleLock} so concurrent callers can't race
     * into opening two connections (see class Javadoc).
     *
     * @param onNotification invoked with each message's payload, parsed as a {@link JsonDocument}
     * @throws NullPointerException if {@code onNotification} is {@code null}
     */
    @Override
    public void start(@NotNull final Consumer<JsonDocument> onNotification) {

        Objects.requireNonNull(onNotification, "@RedisDatabaseNotification.start: onNotification cannot be null");

        synchronized (this.lifecycleLock) {

            if (this.listenerThread != null) return;

            this.subscriberConnection = this.openConnection();

            this.pubSub = new JedisPubSub() {
                @Override
                public void onMessage(final String channel, final String message) {
                    try {
                        onNotification.accept(new JsonDocument(message));
                    } catch (final Exception exception) {
                        // log, don't propagate - an uncaught exception here would kill the
                        // subscription with no reconnect logic, see class Javadoc
                        exception.printStackTrace();
                    }
                }
            };

            this.running = true;
            this.listenerThread = new Thread(this::listen, this.channel + "-redis-change-notifier");
            this.listenerThread.setDaemon(true);
            this.listenerThread.start();

        }

    }

    private Jedis openConnection() {

        final Jedis jedis;

        if (this.credentials.getUserName().isEmpty() && this.credentials.getPassword().isEmpty()) {
            jedis = new Jedis(this.credentials.getAddress(), this.credentials.getPort());
            jedis.select(Integer.parseInt(this.credentials.getDatabase()));
        } else {
            jedis = new Jedis("redis://:" + this.credentials.getPassword() + "@" + this.credentials.getAddress() + ":" + this.credentials.getPort() + "/" + this.credentials.getDatabase());
        }

        return jedis;

    }

    private void listen() {
        try {
            // Blocks the calling thread until shutdown() unsubscribes or the connection breaks -
            // never busy-waits or sleeps in between.
            this.subscriberConnection.subscribe(this.pubSub, this.channel);
        } catch (final Exception exception) {
            if (!this.running) return; // shutdown() closed the connection on purpose
            exception.printStackTrace();
        }
    }

    /**
     * Stops the listener thread and closes its dedicated subscriber connection, but leaves
     * {@link #credentials} untouched, since this instance does not own their lifecycle. Waits up
     * to 2 seconds for the listener thread to actually exit. Idempotent - safe to call more than
     * once, safe to call before {@link #start}. Once this returns, {@link #start} can be called
     * again to open a fresh listener.
     */
    @Override
    public void shutdown() {

        synchronized (this.lifecycleLock) {

            this.running = false;

            final JedisPubSub subscription = this.pubSub;
            if (subscription != null) {
                try {
                    subscription.unsubscribe();
                } catch (final Exception ignored) {
                    // unsubscribing an already-broken subscription is not an error condition here
                }
            }

            final Jedis connection = this.subscriberConnection;
            if (connection != null) {
                try {
                    connection.close();
                } catch (final Exception ignored) {
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
            this.pubSub = null;
            this.subscriberConnection = null;

        }

    }

    /**
     * @return the Redis Pub/Sub channel this instance was constructed to subscribe on
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
     * @return the daemon thread {@link #start} spawned to block on incoming Pub/Sub messages
     * @throws IllegalStateException if {@link #start} has not been called yet
     */
    @Override
    public @NotNull Thread getThread() {
        final Thread thread = this.listenerThread;
        if (thread == null) throw new IllegalStateException("@RedisDatabaseNotification.getThread: start() has not been called yet");
        return thread;
    }

}
