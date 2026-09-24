package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

/**
 * The runtime-modifiable assault configuration, edited through the Dev UI / JSON-RPC and read by every assault hook.
 * <p>
 * The state is an immutable {@link AssaultSettings} replaced as a whole on every change (copy-on-write under a write
 * lock): multi-field updates -- a profile switch, a reset, an import -- are published atomically, and a concurrent reader
 * never observes them half applied. Individual getters each read the latest state; a hook that reads several values for
 * one request takes a {@link #snapshot()} first, so all its reads come from the same state.
 */
public class MutableAssaultConfig {

    private static final Logger LOG = Logger.getLogger(MutableAssaultConfig.class);
    private static final Map<String, String> EXCEPTION_CLASS_ERRORS = new ConcurrentHashMap<>();

    /**
     * Exception classes offered as quick picks in the Dev UI. Every entry must satisfy the same rules enforced by
     * {@link #checkExceptionClass(String)}: it must be loadable, extend {@link RuntimeException} (the filter layer can only
     * throw unchecked exceptions), and expose a single-{@code String} constructor. This guarantees that picking one never
     * triggers the engine's fallback to {@code RuntimeException}.
     */
    public static final List<String> EXCEPTION_PRESETS = List.of(
            "java.lang.RuntimeException",
            "java.lang.IllegalStateException",
            "java.lang.IllegalArgumentException",
            "java.lang.UnsupportedOperationException",
            "jakarta.ws.rs.WebApplicationException",
            "jakarta.ws.rs.InternalServerErrorException");

    /**
     * Upper bound accepted for any latency value: a larger delay would pin a worker thread for longer than any realistic
     * resilience scenario needs and can starve the worker pool.
     */
    public static final long MAX_LATENCY_MS = 300_000;

    /**
     * A single response header injection rule: the {@link ResponseHeaderAction} applied to the named header and the
     * value written by {@code SET}.
     */
    public record HeaderRule(ResponseHeaderAction action, String value) {
    }

    private final Object writeLock = new Object();
    private final boolean frozen;
    private volatile AssaultSettings state;
    private Runnable onChange;

    /**
     * Creates a configuration holding the built-in defaults.
     */
    public MutableAssaultConfig() {
        this(AssaultSettings.DEFAULTS, false);
    }

    private MutableAssaultConfig(AssaultSettings state, boolean frozen) {
        this.state = state;
        this.frozen = frozen;
    }

    /**
     * Builds a mutable copy of the configuration from {@link GoblinConfig}, applying profile defaults when a
     * non-{@code NONE} profile is selected. Values are not validated here: the engine runs {@link #validateAndFix()}.
     *
     * @param config the configuration
     * @return a new mutable configuration reflecting the configured values and profile defaults
     */
    public static MutableAssaultConfig fromConfig(GoblinConfig config) {
        AssaultSettings.Builder b = new AssaultSettings.Builder();
        AssaultType type = config.assault().type();
        b.latencyEnabled = (type == AssaultType.LATENCY);
        b.exceptionEnabled = (type == AssaultType.EXCEPTION);
        b.httpStatusEnabled = (type == AssaultType.HTTP_STATUS);
        b.dependencyDegradationEnabled = (type == AssaultType.DEPENDENCY_DEGRADATION);
        b.responseBodyEnabled = (type == AssaultType.RESPONSE_BODY);
        b.responseHeaderEnabled = (type == AssaultType.RESPONSE_HEADER);
        b.latencyMinMs = config.assault().latency().minMilliseconds();
        b.latencyMaxMs = config.assault().latency().maxMilliseconds();
        b.exceptionType = config.assault().exception().type();
        b.exceptionMessage = config.assault().exception().message();
        b.httpStatusCode = config.assault().httpStatus().code();
        b.httpStatusMessage = config.assault().httpStatus().message();
        b.responseBodyMode = config.assault().body().mode();
        b.responseBodyPercentage = config.assault().body().percentage();
        Map<String, GoblinConfig.HeaderConfig> headers = config.assault().headers();
        if (headers != null) {
            headers.forEach((name, header) -> {
                try {
                    putHeaderRule(b, name, header.action(), header.value());
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Ignoring invalid response header rule '%s' from configuration: %s", name, e.getMessage());
                }
            });
        }
        b.targetLevel = config.target().level();
        b.profile = config.assault().profile();
        if (b.profile != AssaultProfile.NONE) {
            applyProfileDefaults(b);
        }
        return new MutableAssaultConfig(b.build(), false);
    }

    /**
     * Returns a read-only view frozen on the current state. Every getter of the view reads the same state, whatever
     * changes happen meanwhile; its setters throw {@link UnsupportedOperationException}. Hooks take one snapshot per
     * request so all their reads are consistent.
     *
     * @return a frozen, read-only copy of the current configuration
     */
    public MutableAssaultConfig snapshot() {
        return frozen ? this : new MutableAssaultConfig(state, true);
    }

    /**
     * Derives the next state from the current one and publishes it atomically.
     *
     * @param change the modification applied to a working copy of the current state
     * @param validate whether the working copy is validated (and fixed) before being published
     * @param notify whether the change listener is notified once the new state is published
     * @return the validation issues, empty when {@code validate} is {@code false} or the state was clean
     */
    private List<String> update(Consumer<AssaultSettings.Builder> change, boolean validate, boolean notify) {
        if (frozen) {
            throw new UnsupportedOperationException("A configuration snapshot is read-only");
        }
        List<String> issues;
        synchronized (writeLock) {
            AssaultSettings.Builder b = state.toBuilder();
            change.accept(b);
            issues = validate ? validate(b) : List.of();
            state = b.build();
        }
        if (notify) {
            notifyChange();
        }
        return issues;
    }

    private List<String> update(Consumer<AssaultSettings.Builder> change) {
        return update(change, false, true);
    }

    private List<String> updateAndValidate(Consumer<AssaultSettings.Builder> change) {
        return update(change, true, true);
    }

    /**
     * Validates the current state, fixing invalid values (with a log for each one).
     *
     * @return a human-readable description of each fixed value, empty when the state was clean
     */
    public List<String> validateAndFix() {
        return update(b -> {
        }, true, false);
    }

    private static List<String> validate(AssaultSettings.Builder b) {
        List<String> issues = new ArrayList<>();
        long min = clampLatency(b.latencyMinMs);
        long max = clampLatency(b.latencyMaxMs);
        if (min != b.latencyMinMs || max != b.latencyMaxMs) {
            warn(issues, "Invalid latency range: " + b.latencyMinMs + " - " + b.latencyMaxMs
                    + " ms is outside the valid range 0-" + MAX_LATENCY_MS + " ms. Clamping to " + min + " - " + max
                    + " ms.");
        }
        if (min > max) {
            warn(issues, "Invalid latency range: min-milliseconds (" + min + ") is greater than max-milliseconds ("
                    + max + "). Swapping values.");
            long tmp = min;
            min = max;
            max = tmp;
        }
        b.latencyMinMs = min;
        b.latencyMaxMs = max;
        if (b.httpStatusCode < 100 || b.httpStatusCode > 599) {
            String message = "Invalid HTTP status code: " + b.httpStatusCode
                    + " is outside the valid range 100-599. Defaulting to 503.";
            LOG.errorf("%s", message);
            issues.add(message);
            b.httpStatusCode = 503;
        }
        String exceptionError = exceptionClassError(b.exceptionType);
        if (exceptionError != null) {
            LOG.errorf("%s", exceptionError);
            issues.add(exceptionError);
        }
        int clamped = Math.max(0, Math.min(100, b.targetLevel));
        if (clamped != b.targetLevel) {
            warn(issues, "Invalid target level: " + b.targetLevel + " is outside the valid range 0-100. Clamping to "
                    + clamped + ".");
            b.targetLevel = clamped;
        }
        int percentage = b.responseBodyPercentage;
        if (b.responseBodyMode == ResponseBodyMode.TRUNCATE && (percentage < 0 || percentage > 100)) {
            int clampedPercentage = Math.max(0, Math.min(100, percentage));
            warn(issues, "Invalid response body percentage: " + percentage
                    + " is outside the valid range 0-100 for TRUNCATE. Clamping to " + clampedPercentage + ".");
            b.responseBodyPercentage = clampedPercentage;
        }
        if (b.responseBodyMode == ResponseBodyMode.INFLATE && (percentage < 101 || percentage > 1000)) {
            int clampedPercentage = Math.max(101, Math.min(1000, percentage));
            warn(issues, "Invalid response body percentage: " + percentage
                    + " is outside the valid range 101-1000 for INFLATE. Clamping to " + clampedPercentage + ".");
            b.responseBodyPercentage = clampedPercentage;
        }
        return issues;
    }

    private static void warn(List<String> issues, String message) {
        LOG.warnf("%s", message);
        issues.add(message);
    }

    private static String exceptionClassError(String className) {
        if (className == null || className.isBlank()) {
            return "Configured exception class is blank. The engine will fall back to RuntimeException.";
        }
        String cached = EXCEPTION_CLASS_ERRORS.get(className);
        if (cached != null) {
            return cached;
        }
        String error = checkExceptionClass(className);
        // only failures are cached, and the cache is bounded: a class that becomes loadable later (dev-mode reload) is
        // re-checked, and arbitrary names typed in the Dev UI can never grow the map without limit
        if (error != null && EXCEPTION_CLASS_ERRORS.size() < 256) {
            EXCEPTION_CLASS_ERRORS.put(className, error);
        }
        return error;
    }

    private static String checkExceptionClass(String className) {
        try {
            Class<?> clazz = loadClass(className);
            clazz.getConstructor(String.class);
            if (!RuntimeException.class.isAssignableFrom(clazz)) {
                return "Configured exception class '" + className
                        + "' does not extend RuntimeException. The engine will fall back to RuntimeException.";
            }
            return null;
        } catch (ClassNotFoundException e) {
            return "Configured exception class '" + className
                    + "' could not be found. The engine will fall back to RuntimeException.";
        } catch (NoSuchMethodException e) {
            return "Configured exception class '" + className
                    + "' has no String constructor. The engine will fall back to RuntimeException.";
        }
    }

    /**
     * Loads a class by name <em>without initialising it</em>, resolving application classes through the thread context
     * class loader first (in dev mode the extension runtime lives in the base class loader, which cannot see
     * application classes), then the extension's own class loader.
     *
     * @param className the fully qualified class name
     * @return the loaded, uninitialised class
     * @throws ClassNotFoundException when no class loader can load the class
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            try {
                return Class.forName(className, false, tccl);
            } catch (ClassNotFoundException e) {
                // fall back to the extension class loader
            }
        }
        return Class.forName(className, false, MutableAssaultConfig.class.getClassLoader());
    }

    private static long clampLatency(long value) {
        return Math.max(0, Math.min(MAX_LATENCY_MS, value));
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public boolean isLatencyEnabled() {
        return state.latencyEnabled;
    }

    public void setLatencyEnabled(boolean latencyEnabled) {
        update(b -> b.latencyEnabled = latencyEnabled);
    }

    public boolean isExceptionEnabled() {
        return state.exceptionEnabled;
    }

    public void setExceptionEnabled(boolean exceptionEnabled) {
        update(b -> b.exceptionEnabled = exceptionEnabled);
    }

    public boolean isHttpStatusEnabled() {
        return state.httpStatusEnabled;
    }

    public void setHttpStatusEnabled(boolean httpStatusEnabled) {
        update(b -> b.httpStatusEnabled = httpStatusEnabled);
    }

    public boolean isDependencyDegradationEnabled() {
        return state.dependencyDegradationEnabled;
    }

    public void setDependencyDegradationEnabled(boolean dependencyDegradationEnabled) {
        update(b -> b.dependencyDegradationEnabled = dependencyDegradationEnabled);
    }

    /**
     * @return whether the client-side latency assault is enabled for outgoing REST Client calls
     */
    public boolean isClientLatencyEnabled() {
        return state.clientLatencyEnabled;
    }

    /**
     * Toggles the client-side latency assault, which sleeps on outbound REST Client calls.
     *
     * @param clientLatencyEnabled {@code true} to inject latency on outgoing calls, {@code false} to disable
     */
    public void setClientLatencyEnabled(boolean clientLatencyEnabled) {
        update(b -> b.clientLatencyEnabled = clientLatencyEnabled);
    }

    /**
     * @return whether the client-side exception assault is enabled for outgoing REST Client calls
     */
    public boolean isClientExceptionEnabled() {
        return state.clientExceptionEnabled;
    }

    /**
     * Toggles the client-side exception assault, which throws before outbound REST Client requests are dispatched.
     *
     * @param clientExceptionEnabled {@code true} to throw on outgoing calls, {@code false} to disable
     */
    public void setClientExceptionEnabled(boolean clientExceptionEnabled) {
        update(b -> b.clientExceptionEnabled = clientExceptionEnabled);
    }

    /**
     * @return whether the response body assault is enabled for incoming requests returning an entity
     */
    public boolean isResponseBodyEnabled() {
        return state.responseBodyEnabled;
    }

    /**
     * Toggles the response body assault, which truncates or inflates the entity returned by the endpoint.
     *
     * @param responseBodyEnabled {@code true} to alter response bodies, {@code false} to leave them untouched
     */
    public void setResponseBodyEnabled(boolean responseBodyEnabled) {
        update(b -> b.responseBodyEnabled = responseBodyEnabled);
    }

    /**
     * @return the transformation applied to the response body, never {@code null}
     */
    public ResponseBodyMode getResponseBodyMode() {
        return state.responseBodyMode;
    }

    /**
     * Changes the response body transformation, re-validating the percentage for the new mode.
     *
     * @param responseBodyMode the transformation to apply ({@code TRUNCATE} or {@code INFLATE}); {@code null} keeps
     *        {@link ResponseBodyMode#TRUNCATE}
     */
    public void setResponseBodyMode(ResponseBodyMode responseBodyMode) {
        updateAndValidate(b -> b.responseBodyMode = responseBodyMode != null ? responseBodyMode : ResponseBodyMode.TRUNCATE);
    }

    /**
     * @return the target size of the transformed body in percent (0-100 for {@code TRUNCATE}, 101-1000 for
     *         {@code INFLATE})
     */
    public int getResponseBodyPercentage() {
        return state.responseBodyPercentage;
    }

    /**
     * Changes the response body target size in percent, clamping and warning when the value is invalid for the active
     * mode.
     *
     * @param responseBodyPercentage the target size in percent
     * @return a list of human-readable warnings for values that were clamped, empty when the value was accepted as-is
     */
    public List<String> setResponseBodyPercentage(int responseBodyPercentage) {
        return updateAndValidate(b -> b.responseBodyPercentage = responseBodyPercentage);
    }

    /**
     * @return whether the response header injection assault is enabled for incoming request responses
     */
    public boolean isResponseHeaderEnabled() {
        return state.responseHeaderEnabled;
    }

    /**
     * Toggles the response header injection assault, which applies the configured rules to the emitted response.
     *
     * @param responseHeaderEnabled {@code true} to alter response headers, {@code false} to leave them untouched
     */
    public void setResponseHeaderEnabled(boolean responseHeaderEnabled) {
        update(b -> b.responseHeaderEnabled = responseHeaderEnabled);
    }

    /**
     * @return an unmodifiable view of the configured header rules, keyed by header name, in insertion order
     */
    public Map<String, HeaderRule> getResponseHeaders() {
        return state.responseHeaders;
    }

    /**
     * Adds or replaces the rule applied to the named response header.
     * <p>
     * Header names are case-insensitive: an existing rule for the same name in a different casing is replaced, and the
     * configured casing is kept for the emitted header. The replacement is published atomically, so concurrent readers
     * never observe an intermediate state where the rule is absent.
     *
     * @param name the header name, never {@code null} or blank
     * @param action the action to apply, never {@code null}
     * @param value the header value written by {@code SET}; may be {@code null} to emit a bare header
     * @throws IllegalArgumentException when the header name is blank or not an HTTP token, the action is {@code null},
     *         or the value contains characters that cannot be emitted in an HTTP header
     */
    public void setResponseHeader(String name, ResponseHeaderAction action, String value) {
        checkHeaderRule(name, action, value);
        update(b -> putHeaderRule(b, name, action, value));
        LOG.debugf("Goblin: response header rule stored: %s %s value='%s'", name, action, value);
    }

    /**
     * Stores a header rule without any validation, to simulate a rule that reached the configuration outside this API.
     * Package-private for unit tests only.
     *
     * @param name the header name
     * @param rule the rule
     */
    void putRawHeaderRuleForTests(String name, HeaderRule rule) {
        update(b -> b.responseHeaders.put(name, rule), false, false);
    }

    private static void putHeaderRule(AssaultSettings.Builder b, String name, ResponseHeaderAction action, String value) {
        checkHeaderRule(name, action, value);
        b.responseHeaders.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        b.responseHeaders.put(name, new HeaderRule(action, value != null ? value : ""));
    }

    private static void checkHeaderRule(String name, ResponseHeaderAction action, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Response header name must not be blank");
        }
        if (!isValidResponseHeaderName(name)) {
            throw new IllegalArgumentException("Response header name '" + name
                    + "' is not a valid HTTP token (RFC 9110: letters, digits and !#$%&'*+-.^_`|~ only)");
        }
        if (action == null) {
            throw new IllegalArgumentException("Response header action must not be null");
        }
        if (!isValidResponseHeaderValue(value != null ? value : "")) {
            throw new IllegalArgumentException("Response header value for '" + name
                    + "' contains characters that cannot be emitted in an HTTP header");
        }
    }

    /**
     * Checks that a header name is a valid HTTP {@code token} (RFC 9110 section 5.1), so no control character, space or
     * separator can reach the response.
     *
     * @param name the header name
     * @return {@code true} when the name is a non-empty token
     */
    public static boolean isValidResponseHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates that a value can be safely emitted as an HTTP header: it must not contain CR, LF, DEL or any other
     * control character, except for the horizontal tab. This prevents the chaos configuration from producing an invalid
     * response or a header-injection/splitting condition.
     *
     * @param value the header value, may be {@code null}
     * @return {@code true} when the value is safe to emit
     */
    public static boolean isValidResponseHeaderValue(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0x7F || (c < 0x20 && c != '\t')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the injection rule for the named header, leaving the response untouched.
     * <p>
     * Header names are case-insensitive, so a rule configured under a different casing is removed as well.
     *
     * @param name the header name
     */
    public void removeResponseHeader(String name) {
        if (name == null || state.responseHeaders.keySet().stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
            return;
        }
        update(b -> b.responseHeaders.keySet().removeIf(existing -> existing.equalsIgnoreCase(name)));
        LOG.debugf("Goblin: response header rule removed: %s", name);
    }

    /**
     * @return a human-readable description of the configured header rules, or {@code "none"} when none are configured
     */
    public String describeResponseHeaders() {
        return describeResponseHeaders(state);
    }

    private static String describeResponseHeaders(AssaultSettings s) {
        if (s.responseHeaders.isEmpty()) {
            return "none";
        }
        return s.responseHeaders.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue().action().name().toLowerCase()
                        + (entry.getValue().action() != ResponseHeaderAction.REMOVE
                                ? " \"" + entry.getValue().value() + "\""
                                : ""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public boolean hasAnyAssaultEnabled() {
        AssaultSettings s = state;
        return s.latencyEnabled || s.exceptionEnabled || s.httpStatusEnabled || s.dependencyDegradationEnabled
                || s.responseBodyEnabled || s.responseHeaderEnabled;
    }

    /**
     * @return whether at least one client-side assault is enabled for outbound REST Client calls
     */
    public boolean hasAnyClientAssaultEnabled() {
        AssaultSettings s = state;
        return s.clientLatencyEnabled || s.clientExceptionEnabled;
    }

    /**
     * @return the active predefined composite assault profile, never {@code null}
     */
    public AssaultProfile getProfile() {
        return state.profile;
    }

    /**
     * Switches the active profile and applies its assault defaults.
     * <p>
     * Selecting a non-{@code NONE} profile resets the individual assault toggles to the profile's defaults; each toggle
     * can then be overridden manually on top of the profile. Selecting {@code NONE} leaves the toggles untouched. The
     * profile and its defaults are published as a single state, so no reader ever sees a half-applied profile.
     *
     * @param profile the profile to activate, or {@code null} to keep {@link AssaultProfile#NONE}
     * @return the effective active profile
     */
    public AssaultProfile setProfile(AssaultProfile profile) {
        AssaultProfile effective = profile != null ? profile : AssaultProfile.NONE;
        update(b -> {
            b.profile = effective;
            if (effective != AssaultProfile.NONE) {
                applyProfileDefaults(b);
            }
        });
        return effective;
    }

    /**
     * Restores the profile label without applying its defaults, used when loading persisted state where the individual
     * toggles already reflect the effective (possibly user-overridden) configuration.
     *
     * @param profile the profile to restore, or {@code null} to keep {@link AssaultProfile#NONE}
     */
    void restoreProfile(AssaultProfile profile) {
        update(b -> b.profile = profile != null ? profile : AssaultProfile.NONE, false, false);
    }

    /**
     * Resets the individual assault toggles and parameters to the defaults of the profile held by the builder.
     *
     * @param b the working copy to modify
     */
    private static void applyProfileDefaults(AssaultSettings.Builder b) {
        b.latencyEnabled = false;
        b.exceptionEnabled = false;
        b.httpStatusEnabled = false;
        b.dependencyDegradationEnabled = false;
        b.responseHeaderEnabled = false;
        switch (b.profile) {
            case SLOW_FAILURE -> {
                b.latencyEnabled = true;
                b.exceptionEnabled = true;
                b.latencyMinMs = 100;
                b.latencyMaxMs = 5000;
                b.exceptionType = "java.lang.RuntimeException";
            }
            case INTERMITTENT -> {
                b.httpStatusEnabled = true;
                b.httpStatusCode = 500;
            }
            case TIMEOUT -> {
                b.latencyEnabled = true;
                b.latencyMinMs = 30000;
                b.latencyMaxMs = 30000;
            }
            case NONE -> {
            }
        }
    }

    /**
     * Produces a human-readable summary of the currently enabled assaults, including the active profile if any.
     * <p>
     * Client-side assaults are reported separately with a {@code client} prefix, e.g.
     * {@code "client latency enabled (100 - 500 ms)"}.
     *
     * @return a comma-separated description, or {@code "no assault enabled"} when every toggle is off
     */
    public String describeAssaults() {
        AssaultSettings s = state;
        List<String> parts = new ArrayList<>();
        if (s.latencyEnabled) {
            parts.add("latency enabled (" + s.latencyMinMs + " - " + s.latencyMaxMs + " ms)");
        }
        if (s.exceptionEnabled) {
            parts.add("exception enabled (" + s.exceptionType + ": \"" + s.exceptionMessage + "\")");
        }
        if (s.httpStatusEnabled) {
            parts.add("httpStatus enabled (" + s.httpStatusCode + ": \"" + s.httpStatusMessage + "\")");
        }
        if (s.dependencyDegradationEnabled) {
            parts.add("dependencyDegradation enabled (HTTP 503)");
        }
        if (s.clientLatencyEnabled) {
            parts.add("client latency enabled (" + s.latencyMinMs + " - " + s.latencyMaxMs + " ms)");
        }
        if (s.clientExceptionEnabled) {
            parts.add("client exception enabled (" + s.exceptionType + ": \"" + s.exceptionMessage + "\")");
        }
        if (s.responseBodyEnabled) {
            parts.add("response body " + s.responseBodyMode.name().toLowerCase() + " enabled ("
                    + s.responseBodyPercentage + "%)");
        }
        if (s.responseHeaderEnabled) {
            parts.add("response header enabled (" + describeResponseHeaders(s) + ")");
        }
        if (parts.isEmpty()) {
            return "no assault enabled";
        }
        if (s.profile != AssaultProfile.NONE) {
            parts.add(0, "profile " + s.profile);
        }
        return String.join(", ", parts);
    }

    public long getLatencyMinMs() {
        return state.latencyMinMs;
    }

    /**
     * Sets the lower latency bound, clamped to {@code 0}-{@value #MAX_LATENCY_MS} ms. The bounds are deliberately not
     * reordered here so that updating min then max in two calls never swaps an intermediate state; the latency draw
     * tolerates a reversed range.
     *
     * @param latencyMinMs the lower bound in milliseconds
     */
    public void setLatencyMinMs(long latencyMinMs) {
        long clamped = clampLatency(latencyMinMs);
        if (clamped != latencyMinMs) {
            LOG.warnf("Invalid latency min-milliseconds %d, clamping to %d", latencyMinMs, clamped);
        }
        update(b -> b.latencyMinMs = clamped);
    }

    /**
     * Sets both latency bounds at once, then validates the range (clamping, and swapping an inverted pair).
     *
     * @param latencyMinMs the lower bound in milliseconds
     * @param latencyMaxMs the upper bound in milliseconds
     * @return a list of human-readable warnings for values that were fixed, empty when the range was accepted as-is
     */
    public List<String> setLatencyRange(long latencyMinMs, long latencyMaxMs) {
        return updateAndValidate(b -> {
            b.latencyMinMs = latencyMinMs;
            b.latencyMaxMs = latencyMaxMs;
        });
    }

    /**
     * Returns both latency bounds, read from a single state.
     *
     * @return a two-element array {@code [min, max]} in milliseconds
     */
    public long[] getLatencyRange() {
        AssaultSettings s = state;
        return new long[] { s.latencyMinMs, s.latencyMaxMs };
    }

    public long getLatencyMaxMs() {
        return state.latencyMaxMs;
    }

    /**
     * Sets the upper latency bound, clamped to {@code 0}-{@value #MAX_LATENCY_MS} ms. See
     * {@link #setLatencyMinMs(long)} for why the bounds are not reordered.
     *
     * @param latencyMaxMs the upper bound in milliseconds
     */
    public void setLatencyMaxMs(long latencyMaxMs) {
        long clamped = clampLatency(latencyMaxMs);
        if (clamped != latencyMaxMs) {
            LOG.warnf("Invalid latency max-milliseconds %d, clamping to %d", latencyMaxMs, clamped);
        }
        update(b -> b.latencyMaxMs = clamped);
    }

    public String getExceptionType() {
        return state.exceptionType;
    }

    /**
     * Changes the exception class thrown by the exception assaults. A blank value is rejected with a warning and the
     * current type is kept.
     *
     * @param exceptionType the fully qualified class name
     * @return a list of human-readable warnings, empty when the type was accepted as-is
     */
    public List<String> setExceptionType(String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            String message = "Exception type must not be blank. Keeping '" + state.exceptionType + "'.";
            LOG.warnf("%s", message);
            return List.of(message);
        }
        return updateAndValidate(b -> b.exceptionType = exceptionType.trim());
    }

    public String getExceptionMessage() {
        return state.exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        update(b -> b.exceptionMessage = exceptionMessage);
    }

    public int getHttpStatusCode() {
        return state.httpStatusCode;
    }

    public List<String> setHttpStatusCode(int httpStatusCode) {
        return updateAndValidate(b -> b.httpStatusCode = httpStatusCode);
    }

    public String getHttpStatusMessage() {
        return state.httpStatusMessage;
    }

    public void setHttpStatusMessage(String httpStatusMessage) {
        update(b -> b.httpStatusMessage = httpStatusMessage);
    }

    public int getTargetLevel() {
        return state.targetLevel;
    }

    public List<String> setTargetLevel(int targetLevel) {
        return updateAndValidate(b -> b.targetLevel = targetLevel);
    }

    /**
     * @return a copy of the armed layers
     */
    public Set<ChaosLayer> getLayers() {
        return AssaultSettings.copyOf(state.layers);
    }

    /**
     * Replaces the armed layer set. A {@code null} or empty collection restores the legacy default of inbound and
     * outbound HTTP.
     *
     * @param layers the layers to arm, or {@code null} to restore the default
     */
    public void setLayers(Collection<ChaosLayer> layers) {
        update(b -> b.layers = layers == null || layers.isEmpty()
                ? EnumSet.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT)
                : EnumSet.copyOf(layers));
    }

    /**
     * Arms or disarms a single layer, leaving the others untouched.
     *
     * @param layer the layer to toggle
     * @param enabled {@code true} to arm the layer, {@code false} to disarm it
     */
    public void setLayerEnabled(ChaosLayer layer, boolean enabled) {
        if (layer == null) {
            return;
        }
        update(b -> {
            if (enabled) {
                b.layers.add(layer);
            } else {
                b.layers.remove(layer);
            }
        });
    }

    /**
     * @param layer the layer to test
     * @return whether the given layer is armed
     */
    public boolean isLayerEnabled(ChaosLayer layer) {
        return layer != null && state.layers.contains(layer);
    }

    private void notifyChange() {
        Runnable listener = onChange;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Restores every field to its application.properties default (profile {@code NONE}, latency 100-5000 ms, 503, truncate
     * 50 %, level 100 %, all client-side assaults off), as a single state. Persists the restored defaults when a change
     * listener is installed.
     *
     * @return a list of human-readable warnings for any values that were clamped during validation, empty when the defaults
     *         were clean
     */
    public List<String> resetToDefaults() {
        return update(b -> {
            AssaultSettings.Builder defaults = AssaultSettings.DEFAULTS.toBuilder();
            b.profile = defaults.profile;
            b.latencyEnabled = defaults.latencyEnabled;
            b.exceptionEnabled = defaults.exceptionEnabled;
            b.httpStatusEnabled = defaults.httpStatusEnabled;
            b.dependencyDegradationEnabled = defaults.dependencyDegradationEnabled;
            b.clientLatencyEnabled = defaults.clientLatencyEnabled;
            b.clientExceptionEnabled = defaults.clientExceptionEnabled;
            b.responseBodyEnabled = defaults.responseBodyEnabled;
            b.responseHeaderEnabled = defaults.responseHeaderEnabled;
            b.latencyMinMs = defaults.latencyMinMs;
            b.latencyMaxMs = defaults.latencyMaxMs;
            b.exceptionType = defaults.exceptionType;
            b.exceptionMessage = defaults.exceptionMessage;
            b.httpStatusCode = defaults.httpStatusCode;
            b.httpStatusMessage = defaults.httpStatusMessage;
            b.responseBodyMode = defaults.responseBodyMode;
            b.responseBodyPercentage = defaults.responseBodyPercentage;
            b.targetLevel = defaults.targetLevel;
            b.responseHeaders = defaults.responseHeaders;
            b.layers = defaults.layers;
        }, true, true);
    }
}
