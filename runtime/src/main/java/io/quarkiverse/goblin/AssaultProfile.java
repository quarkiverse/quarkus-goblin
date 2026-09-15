package io.quarkiverse.goblin;

/**
 * Predefined composite assault modes.
 * <p>
 * A profile switches a whole set of assault defaults with a single config line instead of tuning every parameter by
 * hand. It is selected through {@code quarkus.goblin.assault.profile} at startup or via the Dev UI at runtime. Once a
 * profile is applied, individual assaults remain explicitly user-overridable per-assault.
 */
public enum AssaultProfile {

    /**
     * No predefined composite: assaults are controlled manually (or through the legacy {@code assault.type} property).
     */
    NONE,

    /**
     * Latency and exception together: requests are delayed, then fail. Simulates a slow then failing service.
     */
    SLOW_FAILURE,

    /**
     * Percentage-based random HTTP 500 responses. Combine with {@code target.level} to control how often.
     */
    INTERMITTENT,

    /**
     * Very high fixed latency, to exercise client-side {@code @Timeout} and fallback rules.
     */
    TIMEOUT
}