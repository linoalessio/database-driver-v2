package de.lino.database.database.notification;

import de.lino.database.database.entity.Serialized;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A push-based, vendor-specific notification channel: implementations watch one or more
 * {@link Serialized} entity types' tables and invoke a callback the instant a row is written,
 * instead of the caller polling on a timer. There is deliberately no vendor-agnostic
 * implementation behind this contract - each backend's own change-notification primitive (e.g.
 * Postgres {@code LISTEN}/{@code NOTIFY}) differs too much to unify, so every implementation
 * lives in {@code database-driver-plugin} under its own vendor package.
 */
public interface DatabaseNotification {

    /**
     * @return the backend-specific channel/topic this instance watches for notifications on
     */
    @NotNull String getChannel();

    /**
     * @return {@code true} between a successful {@link #start} call and the matching {@link #shutdown}
     */
    boolean isRunning();

    /**
     * @return the daemon thread {@link #start} spawned to block on incoming notifications
     * @throws IllegalStateException if {@link #start} has not been called yet
     */
    @NotNull Thread getThread();

    /**
     * Installs whatever backend-specific trigger or subscription is needed so future writes to
     * each given entity type's table raise a notification on {@link #getChannel()}.
     *
     * @param types the entity types whose tables should start notifying this channel
     */
    void watch(@NotNull final Class<? extends Serialized>... types);

    /**
     * Starts listening for notifications on {@link #getChannel()}, invoking {@code onNotification}
     * once per notification received, in the order received. A no-op if already running.
     *
     * @param onNotification invoked with each notification's payload
     */
    void start(@NotNull final Consumer<JsonDocument> onNotification);

    /**
     * Stops listening and releases whatever resources {@link #start} acquired. A no-op if
     * {@link #start} was never called.
     */
    void shutdown();

}
