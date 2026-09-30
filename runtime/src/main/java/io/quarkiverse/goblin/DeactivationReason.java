package io.quarkiverse.goblin;

/**
 * Why chaos is currently off, reported by {@link AssaultEngine#inactiveReason()} and exposed as
 * {@code inactiveReason} in the Dev UI / JSON-RPC status, so neither a developer nor an AI agent has to guess why an
 * application that was assaulting a moment ago is now silent.
 * <p>
 * A manual deactivation ({@link #MANUAL}) and a fired auto-off ({@link #AUTO_OFF}) are decisions taken during the current
 * dev session and therefore survive a live reload; the other three come from the configuration and the launch mode, so
 * they are recomputed at every start.
 */
public enum DeactivationReason {

    /**
     * Chaos was switched off by an explicit action during this dev session: the Dev UI master toggle, the
     * {@code Disable all} kill switch, or a JSON-RPC / Dev MCP {@code setActive(false)} call. The decision is held in a
     * JVM-wide system property and survives the live reloads of the same dev-mode process, so saving a file does not
     * re-arm chaos; it does not survive a new process, which takes the active flag from {@code quarkus.goblin.enabled}
     * again.
     */
    MANUAL("manual"),

    /**
     * The Dev UI auto-off deadline elapsed and the engine switched chaos off, even with the Dev UI closed. The fired
     * state is kept in a JVM-wide system property, so chaos stays off across live reloads until it is switched on again.
     */
    AUTO_OFF("auto-off"),

    /**
     * {@code quarkus.goblin.enabled=false}: chaos starts off at every start and every live reload. An explicit activation
     * (the Dev UI master toggle, {@code setActive(true)}) can still switch it on until the next live reload, which applies
     * the property again.
     */
    DISABLED("disabled"),

    /**
     * {@code quarkus.goblin.test.enabled} is false: an application's test suite is never assaulted just because the
     * extension is on the classpath. A test can still opt in programmatically.
     */
    TEST_MODE("test-mode"),

    /**
     * The application does not run in dev or test mode: chaos never activates in a packaged production application.
     */
    LAUNCH_MODE("launch-mode");

    private final String tag;

    DeactivationReason(String tag) {
        this.tag = tag;
    }

    /**
     * @return the stable lower-case value reported in the Dev UI status, e.g. {@code "auto-off"}
     */
    public String tag() {
        return tag;
    }
}
