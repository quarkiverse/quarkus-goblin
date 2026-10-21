package io.quarkiverse.goblin;

import java.util.Map;
import java.util.Set;

/**
 * A read-only view of the assault configuration: which assaults are enabled, their parameters, the target level and the
 * armed layers. Read-only views are snapshots frozen on one state -- {@link AssaultEngine#configSnapshot()}, the
 * configurations of an {@link AssaultConfigChange}, the configuration an {@code Assault} is applied with -- so all the
 * reads made on one view are consistent. The live, modifiable configuration is the {@link MutableAssaultConfig} of
 * {@link AssaultEngine#getMutableConfig()}, whose getters each read the latest state.
 * <p>
 * Part of the public API covered by semantic versioning. Instances are provided by Goblin: implementing this interface
 * is not supported, and methods may be added to it in a minor version.
 */
public interface AssaultConfig {

    /**
     * A single response header injection rule: the {@link ResponseHeaderAction} applied to the named header and the
     * value written by {@code SET}.
     *
     * @param action the action applied to the header
     * @param value the value written by {@code SET}, empty for a bare header
     */
    record HeaderRule(ResponseHeaderAction action, String value) {
    }

    /**
     * @return whether the latency assault is enabled on inbound requests and the service, database and messaging layers
     */
    boolean isLatencyEnabled();

    /**
     * @return whether the exception assault is enabled on inbound requests and the service, database and messaging layers
     */
    boolean isExceptionEnabled();

    /**
     * @return whether the HTTP status assault is enabled on inbound requests
     */
    boolean isHttpStatusEnabled();

    /**
     * @return whether the dependency degradation assault (HTTP 503) is enabled on inbound requests
     */
    boolean isDependencyDegradationEnabled();

    /**
     * @return whether the client-side latency assault is enabled on outgoing REST Client and {@code WebClient} calls
     */
    boolean isClientLatencyEnabled();

    /**
     * @return whether the client-side exception assault is enabled on outgoing REST Client and {@code WebClient} calls
     */
    boolean isClientExceptionEnabled();

    /**
     * @return whether the response body assault is enabled for inbound requests returning an entity
     */
    boolean isResponseBodyEnabled();

    /**
     * @return whether the response header assault is enabled for inbound requests
     */
    boolean isResponseHeaderEnabled();

    /**
     * @return whether at least one server-side assault is enabled
     */
    boolean hasAnyAssaultEnabled();

    /**
     * @return whether at least one client-side assault is enabled
     */
    boolean hasAnyClientAssaultEnabled();

    /**
     * @return the active profile, never {@code null}
     */
    AssaultProfile getProfile();

    /**
     * @return the lower latency bound, in milliseconds
     */
    long getLatencyMinMs();

    /**
     * @return the upper latency bound, in milliseconds
     */
    long getLatencyMaxMs();

    /**
     * @return the fully qualified name of the exception class thrown by the exception assaults
     */
    String getExceptionType();

    /**
     * @return the message of the thrown exception
     */
    String getExceptionMessage();

    /**
     * @return the status code returned by the HTTP status assault
     */
    int getHttpStatusCode();

    /**
     * @return the message of the HTTP status assault response
     */
    String getHttpStatusMessage();

    /**
     * @return the transformation applied to the response body, never {@code null}
     */
    ResponseBodyMode getResponseBodyMode();

    /**
     * @return the target size of the transformed body in percent (0-100 for {@code TRUNCATE}, 101-1000 for
     *         {@code INFLATE})
     */
    int getResponseBodyPercentage();

    /**
     * @return an unmodifiable view of the response header rules, keyed by header name, in insertion order
     */
    Map<String, HeaderRule> getResponseHeaders();

    /**
     * @return the percentage (0-100) of requests, calls or messages subject to an assault
     */
    int getTargetLevel();

    /**
     * @return a copy of the armed layers
     */
    Set<ChaosLayer> getLayers();

    /**
     * @param layer the layer to test
     * @return whether the layer is armed
     */
    boolean isLayerEnabled(ChaosLayer layer);

    /**
     * Produces a human-readable summary of the enabled assaults, e.g. {@code "latency enabled (100 - 500 ms)"}, with the
     * active profile first when there is one. Meant for display: the format may change in a minor version.
     *
     * @return a comma-separated description, or {@code "no assault enabled"}
     */
    String describeAssaults();
}
