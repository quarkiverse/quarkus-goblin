package io.quarkiverse.goblin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chaos engine: whether chaos is active, the assault configuration, the auto-off, the assault history and the
 * counters. A single instance lives in the CDI container; inject it in an application or a {@code @QuarkusTest} to drive
 * chaos from code:
 *
 * <pre>
 * &#64;Inject
 * AssaultEngine engine;
 *
 * void armLatency() {
 *     engine.getMutableConfig().setLatencyEnabled(true);
 *     engine.setActive(true);
 * }
 * </pre>
 * <p>
 * Chaos is meant for dev and test mode. In any other launch mode the engine is never initialised: it starts inactive,
 * {@link #getMutableConfig()} and {@link #configSnapshot()} return {@code null}, and no assault fires, even after
 * {@link #setActive(boolean) setActive(true)}. Goblin itself never reaches production: a production build carrying
 * the extension fails, so code using this interface lives in tests or in dev-only modules.
 * <p>
 * Part of the public API covered by semantic versioning. The engine is provided by Goblin: implementing this
 * interface is not supported, and methods may be added to it in a minor version.
 */
public interface AssaultEngine {

    /** Longest accepted auto-off delay: 24 hours. */
    long MAX_AUTO_OFF_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * Returns whether chaos is active. An elapsed auto-off deadline deactivates the engine on the spot, so the auto-off
     * applies whether or not the Dev UI is open.
     *
     * @return {@code true} when chaos is active
     */
    boolean isActive();

    /**
     * Returns why chaos is currently off, so a deactivation can be told apart from a misconfiguration.
     *
     * @return the deactivation reason, or {@code null} while chaos is active
     */
    DeactivationReason inactiveReason();

    /**
     * Activates or deactivates chaos. Deactivating cancels any pending auto-off; activating forgets an auto-off that
     * already fired but keeps a pending one. In dev mode the decision survives a live reload, in both directions.
     *
     * @param active the new active flag
     */
    void setActive(boolean active);

    /**
     * Flips the active flag. When the auto-off deadline has just elapsed, the caller saw chaos active and asked to turn
     * it off: the elapsed auto-off is applied and chaos stays off, instead of being switched back on.
     *
     * @return the new active flag
     */
    boolean toggleActive();

    /**
     * Schedules chaos to switch itself off after the given delay, replacing any pending auto-off. The engine enforces it
     * on its own, independently of the Dev UI; in dev mode the deadline survives live reloads.
     *
     * @param delayMillis the delay before chaos is deactivated, between 1 ms and {@link #MAX_AUTO_OFF_MILLIS}
     * @return the deadline, in epoch milliseconds
     * @throws IllegalArgumentException when the delay is out of range
     */
    long scheduleAutoOff(long delayMillis);

    /**
     * Cancels any pending auto-off; chaos stays in its current state.
     */
    void cancelAutoOff();

    /**
     * Returns the pending auto-off deadline.
     *
     * @return the deadline in epoch milliseconds, or {@code 0} when no auto-off is pending
     */
    long autoOffDeadline();

    /**
     * @param layer the layer to test
     * @return whether an assault hook backs the given layer in this application
     */
    boolean isLayerAvailable(ChaosLayer layer);

    /**
     * Returns the layers backed by an assault hook in this application: the built-in ones, plus
     * {@link ChaosLayer#DATABASE} and {@link ChaosLayer#MESSAGING} when the application has the matching extension.
     *
     * @return the available layers, in ascending declaration order
     */
    Set<ChaosLayer> getAvailableLayers();

    /**
     * Returns the live assault configuration. Every change made through it applies to the next request or call, is
     * reported to the {@link AssaultObserver#onConfigChange observers} and, in dev mode, persisted to
     * {@code .goblin-state.json}.
     *
     * @return the live configuration, or {@code null} before startup and in any launch mode other than dev
     *         and test
     */
    MutableAssaultConfig getMutableConfig();

    /**
     * Returns a read-only snapshot of the current configuration: all reads of the snapshot come from the same state,
     * whatever changes meanwhile.
     *
     * @return the frozen configuration, or {@code null} before startup and in any launch mode other than dev
     *         and test
     */
    AssaultConfig configSnapshot();

    /**
     * Returns the most recent assaults, oldest first. The history is bounded: the oldest records are dropped once it is
     * full.
     *
     * @return an immutable copy of the history
     */
    List<AssaultRecord> getHistory();

    /**
     * Empties the assault history. The counters are left untouched, see {@link #resetCounters()}.
     */
    void clearHistory();

    /**
     * Returns the total number of assaults recorded since the engine started or counters were last reset.
     *
     * @return the cumulative assault count
     */
    long getTotalAssaultCount();

    /**
     * Returns the per-label assault counts, keyed by {@link AssaultRecord#type()} (e.g. {@code "latency"},
     * {@code "response-body-truncate"}).
     *
     * @return an unmodifiable snapshot of the per-label counts
     */
    Map<String, Long> getAssaultCounts();

    /**
     * Returns the per-source assault counts, keyed by {@link AssaultSource#tag()} (e.g. {@code "server"},
     * {@code "rest-client"}).
     *
     * @return an unmodifiable snapshot of the per-source counts
     */
    Map<String, Long> getAssaultCountsBySource();

    /**
     * Returns the epoch timestamp (ms) when counters were last reset, or when the engine started.
     *
     * @return the counters start time in epoch milliseconds
     */
    long getCountersSinceEpoch();

    /**
     * Resets all assault counters to zero and restarts the counters clock. The history is left untouched, see
     * {@link #clearHistory()}.
     */
    void resetCounters();

    /**
     * One recorded assault.
     * <p>
     * The accessors are part of the public API; the constructor is not, so that components can be added in a minor
     * version. Records are created by Goblin only.
     *
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "latency"} or {@code "response-body-truncate"}: the value of the
     *        {@code type} metric tag and span attribute, which may carry a detail after the assault type
     * @param timestamp the epoch time (ms) the assault was recorded
     * @param latencyMs the injected latency, {@code 0} when not applicable
     * @param configSnapshot a description of the enabled assaults at that time
     * @param source where the assault was injected
     * @param assaultType the assault type, without the detail the label may carry
     * @param injectedValue what the assault injected, e.g. {@code "842ms"}, {@code "HTTP 503"} or
     *        {@code "java.lang.IllegalStateException: boom"}; empty for a custom assault that injected no latency
     */
    record AssaultRecord(String method, String type, long timestamp, long latencyMs, String configSnapshot,
            AssaultSource source, AssaultType assaultType, String injectedValue) {

        /**
         * @return the metric tag / span attribute value of the source, {@code "server"} when no source was recorded
         */
        public String sourceTag() {
            return (source != null ? source : AssaultSource.SERVER).tag();
        }
    }
}
