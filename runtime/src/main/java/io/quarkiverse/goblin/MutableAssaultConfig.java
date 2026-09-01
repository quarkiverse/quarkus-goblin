package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.List;

public class MutableAssaultConfig {

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

    public boolean isLatencyEnabled() {
        return latencyEnabled;
    }

    public void setLatencyEnabled(boolean latencyEnabled) {
        this.latencyEnabled = latencyEnabled;
    }

    public boolean isExceptionEnabled() {
        return exceptionEnabled;
    }

    public void setExceptionEnabled(boolean exceptionEnabled) {
        this.exceptionEnabled = exceptionEnabled;
    }

    public boolean isHttpStatusEnabled() {
        return httpStatusEnabled;
    }

    public void setHttpStatusEnabled(boolean httpStatusEnabled) {
        this.httpStatusEnabled = httpStatusEnabled;
    }

    public boolean isDependencyDegradationEnabled() {
        return dependencyDegradationEnabled;
    }

    public void setDependencyDegradationEnabled(boolean dependencyDegradationEnabled) {
        this.dependencyDegradationEnabled = dependencyDegradationEnabled;
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
    }

    public long getLatencyMaxMs() {
        return latencyMaxMs;
    }

    public void setLatencyMaxMs(long latencyMaxMs) {
        this.latencyMaxMs = latencyMaxMs;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public String getHttpStatusMessage() {
        return httpStatusMessage;
    }

    public void setHttpStatusMessage(String httpStatusMessage) {
        this.httpStatusMessage = httpStatusMessage;
    }

    public int getTargetLevel() {
        return targetLevel;
    }

    public void setTargetLevel(int targetLevel) {
        this.targetLevel = Math.max(0, Math.min(100, targetLevel));
    }
}
