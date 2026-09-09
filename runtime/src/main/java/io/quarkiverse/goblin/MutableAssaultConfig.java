package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

public class MutableAssaultConfig {

    private static final Logger LOG = Logger.getLogger(MutableAssaultConfig.class);

    private Runnable onChange;

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
        try {
            Class.forName(exceptionType).getConstructor(String.class);
        } catch (ClassNotFoundException e) {
            String message = "Configured exception class '" + exceptionType
                    + "' could not be found. The engine will fall back to RuntimeException.";
            LOG.errorf("%s", message);
            issues.add(message);
        } catch (NoSuchMethodException e) {
            String message = "Configured exception class '" + exceptionType
                    + "' has no String constructor. The engine will fall back to RuntimeException.";
            LOG.errorf("%s", message);
            issues.add(message);
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
        return parts.isEmpty() ? "no assault enabled" : String.join(", ", parts);
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
