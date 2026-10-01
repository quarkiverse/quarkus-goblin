package io.quarkiverse.goblin;

/**
 * Observability hook fired by the {@link AssaultEngine} whenever an assault is recorded, the engine is activated or
 * deactivated, or the assault configuration changes. Implementations are discovered through the CDI container and
 * notified synchronously, on the thread that caused the event -- they must never block nor throw.
 * <p>
 * This SPI is the integration point for optional modules such as Micrometer metrics, OpenTelemetry tracing,
 * post-assault assertions and experiment recording. Every method has a default no-op implementation so a partial
 * implementation is trivially safe to register.
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

    /**
     * Called when a change of the assault configuration is published, with the configuration before and after it --
     * enough to record the exact attack an application went through and to replay it after a fix.
     * <p>
     * A change staged on a {@link MutableAssaultConfig#workingCopy()} and published at once with
     * {@link MutableAssaultConfig#replaceWith(MutableAssaultConfig)} -- an {@code applyConfig} call, a Dev UI import, a
     * profile with its parameters -- is a single notification, however many fields it changes. A single setter call is a
     * notification of its own. Two changes made concurrently, from two threads, can be folded into one notification:
     * the observer then sees the oldest previous and the latest current configuration, and the intermediate state is
     * not reported on its own. A transition is never reported twice.
     * <p>
     * Contract:
     * <ul>
     * <li>the call runs on the thread that changed the configuration (a Dev UI action, a JSON-RPC or Dev MCP call, a
     * test), never on a request thread; it must return quickly and must not block;</li>
     * <li>both configurations of the event are frozen snapshots: reading them is consistent, changing them throws
     * {@link UnsupportedOperationException};</li>
     * <li>it fires in dev and test mode, never in a production build, where Goblin is inert;</li>
     * <li>the configuration loaded at startup is not notified, nor are the startup validation of the configuration
     * ({@link MutableAssaultConfig#validateAndFix()}) and the profile label restored from the persisted state: the first
     * notification is the first change made once the application runs. Activating or deactivating chaos is reported by
     * {@link #onActiveChange(boolean)}, not here;</li>
     * <li>an exception thrown by the observer is logged at {@code WARN} and never reaches the caller that changed the
     * configuration; the other observers are still notified.</li>
     * </ul>
     *
     * @param change the configuration before and after the change
     */
    default void onConfigChange(AssaultConfigChange change) {
    }
}