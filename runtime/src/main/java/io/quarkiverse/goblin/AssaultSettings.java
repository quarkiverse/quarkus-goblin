package io.quarkiverse.goblin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable state of the assault configuration. {@link MutableAssaultConfig} publishes a new instance on every change
 * (copy-on-write through {@link Builder}), so a reader holding one instance always sees a consistent configuration --
 * never a profile half applied, nor a latency range half updated.
 */
final class AssaultSettings {

    static final AssaultSettings DEFAULTS = new Builder().build();

    final AssaultProfile profile;
    final boolean latencyEnabled;
    final boolean exceptionEnabled;
    final boolean httpStatusEnabled;
    final boolean dependencyDegradationEnabled;
    final boolean clientLatencyEnabled;
    final boolean clientExceptionEnabled;
    final boolean responseBodyEnabled;
    final boolean responseHeaderEnabled;
    final long latencyMinMs;
    final long latencyMaxMs;
    final String exceptionType;
    final String exceptionMessage;
    final int httpStatusCode;
    final String httpStatusMessage;
    final ResponseBodyMode responseBodyMode;
    final int responseBodyPercentage;
    final int targetLevel;
    final Map<String, MutableAssaultConfig.HeaderRule> responseHeaders;
    final Set<ChaosLayer> layers;

    private AssaultSettings(Builder builder) {
        this.profile = builder.profile != null ? builder.profile : AssaultProfile.NONE;
        this.latencyEnabled = builder.latencyEnabled;
        this.exceptionEnabled = builder.exceptionEnabled;
        this.httpStatusEnabled = builder.httpStatusEnabled;
        this.dependencyDegradationEnabled = builder.dependencyDegradationEnabled;
        this.clientLatencyEnabled = builder.clientLatencyEnabled;
        this.clientExceptionEnabled = builder.clientExceptionEnabled;
        this.responseBodyEnabled = builder.responseBodyEnabled;
        this.responseHeaderEnabled = builder.responseHeaderEnabled;
        this.latencyMinMs = builder.latencyMinMs;
        this.latencyMaxMs = builder.latencyMaxMs;
        this.exceptionType = builder.exceptionType;
        this.exceptionMessage = builder.exceptionMessage;
        this.httpStatusCode = builder.httpStatusCode;
        this.httpStatusMessage = builder.httpStatusMessage;
        this.responseBodyMode = builder.responseBodyMode != null ? builder.responseBodyMode : ResponseBodyMode.TRUNCATE;
        this.responseBodyPercentage = builder.responseBodyPercentage;
        this.targetLevel = builder.targetLevel;
        this.responseHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(builder.responseHeaders));
        this.layers = Collections.unmodifiableSet(copyOf(builder.layers));
    }

    /**
     * @return a builder initialised with this state, to derive the next state from
     */
    Builder toBuilder() {
        Builder builder = new Builder();
        builder.profile = profile;
        builder.latencyEnabled = latencyEnabled;
        builder.exceptionEnabled = exceptionEnabled;
        builder.httpStatusEnabled = httpStatusEnabled;
        builder.dependencyDegradationEnabled = dependencyDegradationEnabled;
        builder.clientLatencyEnabled = clientLatencyEnabled;
        builder.clientExceptionEnabled = clientExceptionEnabled;
        builder.responseBodyEnabled = responseBodyEnabled;
        builder.responseHeaderEnabled = responseHeaderEnabled;
        builder.latencyMinMs = latencyMinMs;
        builder.latencyMaxMs = latencyMaxMs;
        builder.exceptionType = exceptionType;
        builder.exceptionMessage = exceptionMessage;
        builder.httpStatusCode = httpStatusCode;
        builder.httpStatusMessage = httpStatusMessage;
        builder.responseBodyMode = responseBodyMode;
        builder.responseBodyPercentage = responseBodyPercentage;
        builder.targetLevel = targetLevel;
        builder.responseHeaders = new LinkedHashMap<>(responseHeaders);
        builder.layers = copyOf(layers);
        return builder;
    }

    /**
     * @param layers any layer collection, possibly empty
     * @return a mutable {@link EnumSet} copy ({@link EnumSet#copyOf(java.util.Collection)} rejects an empty non-EnumSet
     *         collection)
     */
    static EnumSet<ChaosLayer> copyOf(Set<ChaosLayer> layers) {
        EnumSet<ChaosLayer> copy = EnumSet.noneOf(ChaosLayer.class);
        copy.addAll(layers);
        return copy;
    }

    /**
     * Mutable working copy of an {@link AssaultSettings}, holding the built-in defaults when created empty. Only ever
     * used under the write lock of {@link MutableAssaultConfig}.
     */
    static final class Builder {
        AssaultProfile profile = AssaultProfile.NONE;
        boolean latencyEnabled = true;
        boolean exceptionEnabled;
        boolean httpStatusEnabled;
        boolean dependencyDegradationEnabled;
        boolean clientLatencyEnabled;
        boolean clientExceptionEnabled;
        boolean responseBodyEnabled;
        boolean responseHeaderEnabled;
        long latencyMinMs = 100;
        long latencyMaxMs = 5000;
        String exceptionType = "java.lang.RuntimeException";
        String exceptionMessage = "Goblin chaos: simulated exception";
        int httpStatusCode = 503;
        String httpStatusMessage = "Service Unavailable (Goblin chaos)";
        ResponseBodyMode responseBodyMode = ResponseBodyMode.TRUNCATE;
        int responseBodyPercentage = 50;
        int targetLevel = 100;
        Map<String, MutableAssaultConfig.HeaderRule> responseHeaders = new LinkedHashMap<>();
        Set<ChaosLayer> layers = EnumSet.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT);

        AssaultSettings build() {
            return new AssaultSettings(this);
        }
    }
}
