package io.quarkiverse.goblin;

import java.util.Map;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Goblin chaos engineering configuration, read at runtime: every value can be overridden when the application starts
 * (environment variable, system property, profile...). The targeting rules, which decide at build time which beans are
 * woven, live in {@link GoblinTargetingConfig}.
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "quarkus.goblin")
public interface GoblinConfig {

    /**
     * Whether the Goblin chaos engineering extension is enabled. Chaos only ever activates in dev mode, and in test mode
     * when {@code quarkus.goblin.test.enabled} is set; in a production build the engine stays inactive whatever this
     * value.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Test mode configuration.
     */
    TestConfig test();

    /**
     * Test mode configuration group.
     */
    @ConfigGroup
    interface TestConfig {

        /**
         * Whether chaos is active when the application runs in test mode ({@code @QuarkusTest}). Off by default, so adding
         * the extension never slows down nor breaks an application's test suite: set it to {@code true} to exercise
         * resilience in tests. When off, the engine still loads its configuration and a test can switch chaos on with
         * {@code AssaultEngine.setActive(true)}.
         */
        @WithDefault("false")
        boolean enabled();
    }

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
         * The type of assault to apply. Valid values: LATENCY, EXCEPTION, HTTP_STATUS, DEPENDENCY_DEGRADATION,
         * RESPONSE_BODY, RESPONSE_HEADER. Ignored when a non-{@code NONE} profile is selected.
         */
        @WithDefault("LATENCY")
        AssaultType type();

        /**
         * Response header injection rules, keyed by header name. Each entry declares the action applied to the named
         * response header ({@code SET} or {@code REMOVE}) and, for {@code SET}, the value to write.
         */
        Map<String, HeaderConfig> headers();

        /**
         * Predefined composite assault mode. Valid values: NONE, SLOW_FAILURE, INTERMITTENT, TIMEOUT.
         * A non-{@code NONE} profile enables a set of assaults with sensible defaults; individual assaults stay
         * user-overridable afterwards.
         */
        @WithDefault("NONE")
        AssaultProfile profile();

        /**
         * Response body assault configuration (only used when type=RESPONSE_BODY).
         */
        BodyConfig body();

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
     * A single response header injection rule.
     */
    @ConfigGroup
    interface HeaderConfig {

        /**
         * The action applied to the named response header: {@code SET} forces the header to be present with the
         * configured value (replacing an existing value or adding it when absent), {@code REMOVE} deletes the header
         * when present.
         */
        @WithDefault("SET")
        ResponseHeaderAction action();

        /**
         * The header value written by {@code SET}; ignored by {@code REMOVE}. May be empty to emit a bare header name.
         */
        @WithDefault("")
        String value();
    }

    /**
     * Response body assault configuration.
     */
    @ConfigGroup
    interface BodyConfig {

        /**
         * Transformation applied to the response body: {@code TRUNCATE} cuts the body, {@code INFLATE} pads it.
         */
        @WithDefault("TRUNCATE")
        ResponseBodyMode mode();

        /**
         * Target size of the transformed body relative to the original, in percent. For {@code TRUNCATE} it is the
         * fraction of the body that is kept (0-100). For {@code INFLATE} it is the final size (values above 100 add
         * padding; values at or below 100 are raised to 101 with a WARN log).
         */
        @WithDefault("50")
        int percentage();
    }

    /**
     * Latency assault configuration.
     */
    @ConfigGroup
    interface LatencyConfig {

        /**
         * Minimum latency in milliseconds. Must be lower than or equal to max-milliseconds; inverted values are swapped with a
         * WARN log at startup. Values outside {@code 0}-{@code 300000} are clamped with a WARN log.
         */
        @WithDefault("100")
        long minMilliseconds();

        /**
         * Maximum latency in milliseconds. Values outside {@code 0}-{@code 300000} are clamped with a WARN log.
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
         * The exception class to throw. Must extend {@link RuntimeException} and have a public String constructor; otherwise
         * the engine falls back to RuntimeException with an ERROR log at startup.
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
         * The HTTP response body sent with the status code.
         */
        @WithDefault("Service Unavailable (Goblin chaos)")
        String message();
    }

    /**
     * Targeting configuration: how many requests are affected. Which classes are eligible is decided at build time, see
     * {@link GoblinTargetingConfig}.
     */
    @ConfigGroup
    interface TargetConfig {

        /**
         * The percentage of requests to affect (0-100).
         */
        @WithDefault("100")
        int level();
    }
}
