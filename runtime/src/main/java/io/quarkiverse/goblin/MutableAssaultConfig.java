package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

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

    private Runnable onChange;

    private final Object profileLock = new Object();
    private volatile AssaultProfile profile = AssaultProfile.NONE;
    private volatile boolean latencyEnabled = true;
    private volatile boolean exceptionEnabled = false;
    private volatile boolean httpStatusEnabled = false;
    private volatile boolean dependencyDegradationEnabled = false;
    private volatile boolean clientLatencyEnabled = false;
    private volatile boolean clientExceptionEnabled = false;
    private volatile boolean responseBodyEnabled = false;
    private volatile boolean responseHeaderEnabled = false;

    private volatile long latencyMinMs = 100;
    private volatile long latencyMaxMs = 5000;
    private volatile String exceptionType = "java.lang.RuntimeException";
    private volatile String exceptionMessage = "Goblin chaos: simulated exception";
    private volatile int httpStatusCode = 503;
    private volatile String httpStatusMessage = "Service Unavailable (Goblin chaos)";
    private volatile ResponseBodyMode responseBodyMode = ResponseBodyMode.TRUNCATE;
    private volatile int responseBodyPercentage = 50;
    private volatile int targetLevel = 100;

    /**
     * A single response header injection rule: the {@link ResponseHeaderAction} applied to the named header and the
     * value written by {@code SET}.
     */
    public record HeaderRule(ResponseHeaderAction action, String value) {
    }

    private final Map<String, HeaderRule> responseHeaders = new ConcurrentHashMap<>();

    /**
     * Builds a mutable copy of the configuration from the static {@link GoblinConfig}, applying profile defaults when a
     * non-{@code NONE} profile is selected.
     *
     * @param config the static configuration
     * @return a new mutable configuration reflecting the static values and profile defaults
     */
    public static MutableAssaultConfig fromConfig(GoblinConfig config) {
        MutableAssaultConfig mutable = new MutableAssaultConfig();
        AssaultType type = config.assault().type();
        mutable.latencyEnabled = (type == AssaultType.LATENCY);
        mutable.exceptionEnabled = (type == AssaultType.EXCEPTION);
        mutable.httpStatusEnabled = (type == AssaultType.HTTP_STATUS);
        mutable.dependencyDegradationEnabled = (type == AssaultType.DEPENDENCY_DEGRADATION);
        mutable.responseBodyEnabled = (type == AssaultType.RESPONSE_BODY);
        mutable.responseHeaderEnabled = (type == AssaultType.RESPONSE_HEADER);
        mutable.latencyMinMs = config.assault().latency().minMilliseconds();
        mutable.latencyMaxMs = config.assault().latency().maxMilliseconds();
        mutable.exceptionType = config.assault().exception().type();
        mutable.exceptionMessage = config.assault().exception().message();
        mutable.httpStatusCode = config.assault().httpStatus().code();
        mutable.httpStatusMessage = config.assault().httpStatus().message();
        mutable.responseBodyMode = config.assault().body().mode();
        mutable.responseBodyPercentage = config.assault().body().percentage();
        Map<String, GoblinConfig.HeaderConfig> headers = config.assault().headers();
        if (headers != null) {
            headers.forEach((name, header) -> mutable.setResponseHeader(name, normalizeAction(header.action()),
                    header.value()));
        }
        mutable.targetLevel = config.target().level();
        mutable.profile = config.assault().profile();
        if (mutable.profile != AssaultProfile.NONE) {
            mutable.applyProfileDefaults();
        }
        return mutable;
    }

    public List<String> validateAndFix() {
        List<String> issues = new ArrayList<>();
        if (latencyMinMs > latencyMaxMs) {
            String message = "Invalid latency range: min-milliseconds (" + latencyMinMs
                    + ") is greater than max-milliseconds (" + latencyMaxMs + "). Swapping values.";
            LOG.warnf("%s", message);
            issues.add(message);
            long tmp = latencyMinMs;
            latencyMinMs = latencyMaxMs;
            latencyMaxMs = tmp;
        }
        if (httpStatusCode < 100 || httpStatusCode > 599) {
            String message = "Invalid HTTP status code: " + httpStatusCode
                    + " is outside the valid range 100-599. Defaulting to 503.";
            LOG.errorf("%s", message);
            issues.add(message);
            httpStatusCode = 503;
        }
        String exceptionError = exceptionClassError(exceptionType);
        if (exceptionError != null) {
            LOG.errorf("%s", exceptionError);
            issues.add(exceptionError);
        }
        int clamped = Math.max(0, Math.min(100, targetLevel));
        if (clamped != targetLevel) {
            String message = "Invalid target level: " + targetLevel + " is outside the valid range 0-100. Clamping to "
                    + clamped + ".";
            LOG.warnf("%s", message);
            issues.add(message);
            targetLevel = clamped;
        }
        int percentage = responseBodyPercentage;
        if (responseBodyMode == ResponseBodyMode.TRUNCATE && (percentage < 0 || percentage > 100)) {
            int clampedPercentage = Math.max(0, Math.min(100, percentage));
            String message = "Invalid response body percentage: " + percentage
                    + " is outside the valid range 0-100 for TRUNCATE. Clamping to " + clampedPercentage + ".";
            LOG.warnf("%s", message);
            issues.add(message);
            responseBodyPercentage = clampedPercentage;
        }
        if (responseBodyMode == ResponseBodyMode.INFLATE && (percentage < 101 || percentage > 1000)) {
            int clampedPercentage = Math.max(101, Math.min(1000, percentage));
            String message = "Invalid response body percentage: " + percentage
                    + " is outside the valid range 101-1000 for INFLATE. Clamping to " + clampedPercentage + ".";
            LOG.warnf("%s", message);
            issues.add(message);
            responseBodyPercentage = clampedPercentage;
        }
        return issues;
    }

    private static String exceptionClassError(String className) {
        return EXCEPTION_CLASS_ERRORS.computeIfAbsent(className, MutableAssaultConfig::checkExceptionClass);
    }

    private static String checkExceptionClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
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

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public boolean isLatencyEnabled() {
        return latencyEnabled;
    }

    public void setLatencyEnabled(boolean latencyEnabled) {
        this.latencyEnabled = latencyEnabled;
        notifyChange();
    }

    public boolean isExceptionEnabled() {
        return exceptionEnabled;
    }

    public void setExceptionEnabled(boolean exceptionEnabled) {
        this.exceptionEnabled = exceptionEnabled;
        notifyChange();
    }

    public boolean isHttpStatusEnabled() {
        return httpStatusEnabled;
    }

    public void setHttpStatusEnabled(boolean httpStatusEnabled) {
        this.httpStatusEnabled = httpStatusEnabled;
        notifyChange();
    }

    public boolean isDependencyDegradationEnabled() {
        return dependencyDegradationEnabled;
    }

    public void setDependencyDegradationEnabled(boolean dependencyDegradationEnabled) {
        this.dependencyDegradationEnabled = dependencyDegradationEnabled;
        notifyChange();
    }

    /**
     * @return whether the client-side latency assault is enabled for outgoing REST Client calls
     */
    public boolean isClientLatencyEnabled() {
        return clientLatencyEnabled;
    }

    /**
     * Toggles the client-side latency assault, which sleeps on outbound REST Client calls.
     *
     * @param clientLatencyEnabled {@code true} to inject latency on outgoing calls, {@code false} to disable
     */
    public void setClientLatencyEnabled(boolean clientLatencyEnabled) {
        this.clientLatencyEnabled = clientLatencyEnabled;
        notifyChange();
    }

    /**
     * @return whether the client-side exception assault is enabled for outgoing REST Client calls
     */
    public boolean isClientExceptionEnabled() {
        return clientExceptionEnabled;
    }

    /**
     * Toggles the client-side exception assault, which throws before outbound REST Client requests are dispatched.
     *
     * @param clientExceptionEnabled {@code true} to throw on outgoing calls, {@code false} to disable
     */
    public void setClientExceptionEnabled(boolean clientExceptionEnabled) {
        this.clientExceptionEnabled = clientExceptionEnabled;
        notifyChange();
    }

    /**
     * @return whether the response body assault is enabled for incoming requests returning an entity
     */
    public boolean isResponseBodyEnabled() {
        return responseBodyEnabled;
    }

    /**
     * Toggles the response body assault, which truncates or inflates the entity returned by the endpoint.
     *
     * @param responseBodyEnabled {@code true} to alter response bodies, {@code false} to leave them untouched
     */
    public void setResponseBodyEnabled(boolean responseBodyEnabled) {
        this.responseBodyEnabled = responseBodyEnabled;
        notifyChange();
    }

    /**
     * @return the transformation applied to the response body, never {@code null}
     */
    public ResponseBodyMode getResponseBodyMode() {
        return responseBodyMode != null ? responseBodyMode : ResponseBodyMode.TRUNCATE;
    }

    /**
     * Changes the response body transformation.
     *
     * @param responseBodyMode the transformation to apply ({@code TRUNCATE} or {@code INFLATE}); {@code null} keeps
     *        {@link ResponseBodyMode#TRUNCATE}
     */
    public void setResponseBodyMode(ResponseBodyMode responseBodyMode) {
        this.responseBodyMode = responseBodyMode != null ? responseBodyMode : ResponseBodyMode.TRUNCATE;
        validateAndFix();
        notifyChange();
    }

    /**
     * @return the target size of the transformed body in percent (0-100 for {@code TRUNCATE}, 101-1000 for
     *         {@code INFLATE})
     */
    public int getResponseBodyPercentage() {
        return responseBodyPercentage;
    }

    /**
     * Changes the response body target size in percent, clamping and warning when the value is invalid for the active
     * mode.
     *
     * @param responseBodyPercentage the target size in percent
     * @return a list of human-readable warnings for values that were clamped, empty when the value was accepted as-is
     */
    public List<String> setResponseBodyPercentage(int responseBodyPercentage) {
        this.responseBodyPercentage = responseBodyPercentage;
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }

    /**
     * @return whether the response header injection assault is enabled for incoming request responses
     */
    public boolean isResponseHeaderEnabled() {
        return responseHeaderEnabled;
    }

    /**
     * Toggles the response header injection assault, which applies the configured rules to the emitted response.
     *
     * @param responseHeaderEnabled {@code true} to alter response headers, {@code false} to leave them untouched
     */
    public void setResponseHeaderEnabled(boolean responseHeaderEnabled) {
        this.responseHeaderEnabled = responseHeaderEnabled;
        notifyChange();
    }

    /**
     * @return an unmodifiable snapshot of the configured header rules, keyed by header name
     */
    public Map<String, HeaderRule> getResponseHeaders() {
        return Map.copyOf(responseHeaders);
    }

    /**
     * Adds or replaces the rule applied to the named response header.
     *
     * @param name the header name, never {@code null} or blank
     * @param action the action to apply, never {@code null}
     * @param value the header value written by {@code SET}; may be {@code null} to emit a bare header
     * @throws IllegalArgumentException when the header name is blank or the action is {@code null}
     */
    public void setResponseHeader(String name, ResponseHeaderAction action, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Response header name must not be blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("Response header action must not be null");
        }
        responseHeaders.put(name, new HeaderRule(action, value != null ? value : ""));
        notifyChange();
    }

    /**
     * Removes the injection rule for the named header, leaving the response untouched.
     *
     * @param name the header name
     */
    public void removeResponseHeader(String name) {
        if (responseHeaders.remove(name) != null) {
            notifyChange();
        }
    }

    /**
     * @return a human-readable description of the configured header rules, or {@code "none"} when none are configured
     */
    public String describeResponseHeaders() {
        if (responseHeaders.isEmpty()) {
            return "none";
        }
        return responseHeaders.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue().action().name().toLowerCase()
                        + (entry.getValue().action() != ResponseHeaderAction.REMOVE
                                ? " \"" + entry.getValue().value() + "\""
                                : ""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static ResponseHeaderAction normalizeAction(ResponseHeaderAction action) {
        return action != null ? action : ResponseHeaderAction.SET;
    }

    public boolean hasAnyAssaultEnabled() {
        return latencyEnabled || exceptionEnabled || httpStatusEnabled || dependencyDegradationEnabled
                || responseBodyEnabled || responseHeaderEnabled;
    }

    /**
     * @return whether at least one client-side assault is enabled for outbound REST Client calls
     */
    public boolean hasAnyClientAssaultEnabled() {
        return clientLatencyEnabled || clientExceptionEnabled;
    }

    /**
     * @return the active predefined composite assault profile, never {@code null}
     */
    public AssaultProfile getProfile() {
        return profile != null ? profile : AssaultProfile.NONE;
    }

    /**
     * Switches the active profile and applies its assault defaults.
     * <p>
     * Selecting a non-{@code NONE} profile resets the individual assault toggles to the profile's defaults; each toggle
     * can then be overridden manually on top of the profile. Selecting {@code NONE} leaves the toggles untouched.
     * <p>
     * The mutation is serialized on a dedicated lock so two concurrent profile switches cannot interleave. Individual
     * toggle reads are not locked: a concurrent reader may briefly observe a partially applied profile update.
     *
     * @param profile the profile to activate, or {@code null} to keep {@link AssaultProfile#NONE}
     * @return the effective active profile
     */
    public AssaultProfile setProfile(AssaultProfile profile) {
        synchronized (profileLock) {
            this.profile = profile != null ? profile : AssaultProfile.NONE;
            if (this.profile != AssaultProfile.NONE) {
                applyProfileDefaults();
            }
        }
        notifyChange();
        return this.profile;
    }

    /**
     * Restores the profile label without applying its defaults, used when loading persisted state where the individual
     * toggles already reflect the effective (possibly user-overridden) configuration.
     *
     * @param profile the profile to restore, or {@code null} to keep {@link AssaultProfile#NONE}
     */
    void restoreProfile(AssaultProfile profile) {
        synchronized (profileLock) {
            this.profile = profile != null ? profile : AssaultProfile.NONE;
        }
    }

    /**
     * Resets all individual assault toggles and parameters to the defaults defined by the active profile.
     * Must be invoked while holding {@link #profileLock} so the reset happens atomically relative to other profile
     * switches.
     */
    private void applyProfileDefaults() {
        latencyEnabled = false;
        exceptionEnabled = false;
        httpStatusEnabled = false;
        dependencyDegradationEnabled = false;
        responseHeaderEnabled = false;
        switch (profile) {
            case SLOW_FAILURE -> {
                latencyEnabled = true;
                exceptionEnabled = true;
                latencyMinMs = 100;
                latencyMaxMs = 5000;
                exceptionType = "java.lang.RuntimeException";
            }
            case INTERMITTENT -> {
                httpStatusEnabled = true;
                httpStatusCode = 500;
            }
            case TIMEOUT -> {
                latencyEnabled = true;
                latencyMinMs = 30000;
                latencyMaxMs = 30000;
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
        List<String> parts = new ArrayList<>();
        if (latencyEnabled) {
            parts.add("latency enabled (" + latencyMinMs + " - " + latencyMaxMs + " ms)");
        }
        if (exceptionEnabled) {
            parts.add("exception enabled (" + exceptionType + ": \"" + exceptionMessage + "\")");
        }
        if (httpStatusEnabled) {
            parts.add("httpStatus enabled (" + httpStatusCode + ": \"" + httpStatusMessage + "\")");
        }
        if (dependencyDegradationEnabled) {
            parts.add("dependencyDegradation enabled (HTTP 503)");
        }
        if (clientLatencyEnabled) {
            parts.add("client latency enabled (" + latencyMinMs + " - " + latencyMaxMs + " ms)");
        }
        if (clientExceptionEnabled) {
            parts.add("client exception enabled (" + exceptionType + ": \"" + exceptionMessage + "\")");
        }
        if (responseBodyEnabled) {
            parts.add("response body " + getResponseBodyMode().name().toLowerCase() + " enabled ("
                    + responseBodyPercentage + "%)");
        }
        if (responseHeaderEnabled) {
            parts.add("response header enabled (" + describeResponseHeaders() + ")");
        }
        if (parts.isEmpty()) {
            return "no assault enabled";
        }
        if (profile != AssaultProfile.NONE) {
            parts.add(0, "profile " + profile);
        }
        return String.join(", ", parts);
    }

    public long getLatencyMinMs() {
        return latencyMinMs;
    }

    public void setLatencyMinMs(long latencyMinMs) {
        this.latencyMinMs = latencyMinMs;
        notifyChange();
    }

    public List<String> setLatencyRange(long latencyMinMs, long latencyMaxMs) {
        this.latencyMinMs = latencyMinMs;
        this.latencyMaxMs = latencyMaxMs;
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }

    public long getLatencyMaxMs() {
        return latencyMaxMs;
    }

    public void setLatencyMaxMs(long latencyMaxMs) {
        this.latencyMaxMs = latencyMaxMs;
        notifyChange();
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public List<String> setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
        notifyChange();
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public List<String> setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }

    public String getHttpStatusMessage() {
        return httpStatusMessage;
    }

    public void setHttpStatusMessage(String httpStatusMessage) {
        this.httpStatusMessage = httpStatusMessage;
        notifyChange();
    }

    public int getTargetLevel() {
        return targetLevel;
    }

    public List<String> setTargetLevel(int targetLevel) {
        this.targetLevel = targetLevel;
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }

    private void notifyChange() {
        if (onChange != null) {
            onChange.run();
        }
    }

    /**
     * Restores every field to its application.properties default (profile {@code NONE}, latency 100-5000 ms, 503, truncate
     * 50 %, level 100 %, all client-side assaults off). Persists the restored defaults when a change listener is installed.
     *
     * @return a list of human-readable warnings for any values that were clamped during validation, empty when the defaults
     *         were clean
     */
    public List<String> resetToDefaults() {
        synchronized (profileLock) {
            profile = AssaultProfile.NONE;
            latencyEnabled = true;
            exceptionEnabled = false;
            httpStatusEnabled = false;
            dependencyDegradationEnabled = false;
            clientLatencyEnabled = false;
            clientExceptionEnabled = false;
            responseBodyEnabled = false;
            responseHeaderEnabled = false;
            responseHeaders.clear();
            latencyMinMs = 100;
            latencyMaxMs = 5000;
            exceptionType = "java.lang.RuntimeException";
            exceptionMessage = "Goblin chaos: simulated exception";
            httpStatusCode = 503;
            httpStatusMessage = "Service Unavailable (Goblin chaos)";
            responseBodyMode = ResponseBodyMode.TRUNCATE;
            responseBodyPercentage = 50;
            targetLevel = 100;
        }
        List<String> issues = validateAndFix();
        notifyChange();
        return issues;
    }
}
