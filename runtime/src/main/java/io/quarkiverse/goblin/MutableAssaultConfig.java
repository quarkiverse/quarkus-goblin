package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

public class MutableAssaultConfig {

    private static final Logger LOG = Logger.getLogger(MutableAssaultConfig.class);
    private static final Map<String, String> EXCEPTION_CLASS_ERRORS = new ConcurrentHashMap<>();

    private Runnable onChange;

    private volatile AssaultProfile profile = AssaultProfile.NONE;
    private volatile boolean latencyEnabled = true;
    private volatile boolean exceptionEnabled = false;
    private volatile boolean httpStatusEnabled = false;
    private volatile boolean dependencyDegradationEnabled = false;

    private volatile long latencyMinMs = 100;
    private volatile long latencyMaxMs = 5000;
    private volatile String exceptionType = "java.lang.RuntimeException";
    private volatile String exceptionMessage = "Goblin chaos: simulated exception";
    private volatile int httpStatusCode = 503;
    private volatile String httpStatusMessage = "Service Unavailable (Goblin chaos)";
    private volatile int targetLevel = 100;

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
        mutable.latencyMinMs = config.assault().latency().minMilliseconds();
        mutable.latencyMaxMs = config.assault().latency().maxMilliseconds();
        mutable.exceptionType = config.assault().exception().type();
        mutable.exceptionMessage = config.assault().exception().message();
        mutable.httpStatusCode = config.assault().httpStatus().code();
        mutable.httpStatusMessage = config.assault().httpStatus().message();
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
        return issues;
    }

    private static String exceptionClassError(String className) {
        return EXCEPTION_CLASS_ERRORS.computeIfAbsent(className, MutableAssaultConfig::checkExceptionClass);
    }

    private static String checkExceptionClass(String className) {
        try {
            Class.forName(className).getConstructor(String.class);
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

    public boolean hasAnyAssaultEnabled() {
        return latencyEnabled || exceptionEnabled || httpStatusEnabled || dependencyDegradationEnabled;
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
     *
     * @param profile the profile to activate, or {@code null} to keep {@link AssaultProfile#NONE}
     * @return the effective active profile
     */
    public AssaultProfile setProfile(AssaultProfile profile) {
        this.profile = profile != null ? profile : AssaultProfile.NONE;
        if (this.profile != AssaultProfile.NONE) {
            applyProfileDefaults();
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
        this.profile = profile != null ? profile : AssaultProfile.NONE;
    }

    /**
     * Resets all individual assault toggles and parameters to the defaults defined by the active profile.
     */
    private void applyProfileDefaults() {
        latencyEnabled = false;
        exceptionEnabled = false;
        httpStatusEnabled = false;
        dependencyDegradationEnabled = false;
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
}
