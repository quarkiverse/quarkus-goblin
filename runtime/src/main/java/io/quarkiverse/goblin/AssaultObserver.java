package io.quarkiverse.goblin;

/**
 * Observability hook fired by the {@link AssaultEngine} whenever an assault is recorded or the engine is activated or
 * deactivated. Implementations are discovered through the CDI container and notified synchronously -- they must never
 * throw, as the notification happens on the request path.
 * <p>
 * This SPI is the integration point for optional modules such as Micrometer metrics, OpenTelemetry tracing or
 * post-assault assertions. Every method has a default no-op implementation so a partial implementation is trivially
 * safe to register.
 */
public interface AssaultObserver {

    /**
     * Called after an assault has been recorded in the engine history.
     *
     * @param record the assault record that was just added
     */
    default void onAssault(AssaultEngine.AssaultRecord record) {
    }

    /**
     * Called whenever the engine is activated or deactivated.
     *
     * @param active the new engine state
     */
    default void onActiveChange(boolean active) {
    }
}