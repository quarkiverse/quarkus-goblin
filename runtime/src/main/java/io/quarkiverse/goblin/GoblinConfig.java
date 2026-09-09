package io.quarkiverse.goblin;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Goblin chaos engineering configuration.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.goblin")
public interface GoblinConfig {

    /**
     * Whether the Goblin chaos engineering extension is enabled. Only active in dev mode.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Assault configuration.
     */
    AssaultConfig assault();

    /**
     * Targeting configuration.
     */
    TargetConfig target();

    /**
     * Assault configuration group.
     */
    @ConfigGroup
    interface AssaultConfig {

        /**
         * The type of assault to apply. Valid values: LATENCY, EXCEPTION, HTTP_STATUS, DEPENDENCY_DEGRADATION.
         */
        @WithDefault("LATENCY")
        AssaultType type();

        /**
         * Latency configuration (only used when type=LATENCY).
         */
        LatencyConfig latency();

        /**
         * Exception configuration (only used when type=EXCEPTION).
         */
        ExceptionConfig exception();

        /**
         * HTTP status configuration (only used when type=HTTP_STATUS).
         */
        HttpStatusConfig httpStatus();
    }

    /**
     * Latency assault configuration.
     */
    @ConfigGroup
    interface LatencyConfig {

        /**
         * Minimum latency in milliseconds. Must be lower than or equal to max-milliseconds; inverted values are swapped with a
         * WARN log at startup.
         */
        @WithDefault("100")
        long minMilliseconds();

        /**
         * Maximum latency in milliseconds.
         */
        @WithDefault("5000")
        long maxMilliseconds();
    }

    /**
     * Exception assault configuration.
     */
    @ConfigGroup
    interface ExceptionConfig {

        /**
         * The exception class to throw. Must have a String constructor; otherwise the engine falls back to RuntimeException
         * with an ERROR log at startup.
         */
        @WithDefault("java.lang.RuntimeException")
        String type();

        /**
         * The exception message.
         */
        @WithDefault("Goblin chaos: simulated exception")
        String message();
    }

    /**
     * HTTP status assault configuration.
     */
    @ConfigGroup
    interface HttpStatusConfig {

        /**
         * The HTTP status code to return. Must be in the 100-599 range; out-of-range values default to 503 with an ERROR log at
         * startup.
         */
        @WithDefault("503")
        int code();

        /**
         * The HTTP status reason phrase.
         */
        @WithDefault("Service Unavailable (Goblin chaos)")
        String message();
    }

    /**
     * Targeting configuration for selecting which endpoints are affected.
     */
    @ConfigGroup
    interface TargetConfig {

        /**
         * The percentage of requests to affect (0-100).
         */
        @WithDefault("100")
        int level();

        /**
         * Packages to include (empty means all).
         */
        Optional<String[]> includePackages();

        /**
         * Packages to exclude.
         */
        Optional<String[]> excludePackages();

        /**
         * Annotations to exclude (methods with these annotations are skipped).
         */
        Optional<String[]> excludeAnnotations();
    }
}
