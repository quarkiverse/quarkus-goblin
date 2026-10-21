package io.quarkiverse.goblin;

import java.util.Collection;
import java.util.List;

/**
 * The live assault configuration, modifiable at runtime: {@link AssaultEngine#getMutableConfig()}. The Dev UI, JSON-RPC
 * and Dev MCP edit the same instance.
 * <p>
 * Every change is published atomically and applies to the next request or call. The getters each read the latest
 * state; code that reads several values for one decision takes a {@link #snapshot()} first, so all its reads come from
 * the same state. The setters returning a {@code List<String>} validate the new state and fix invalid values (clamping,
 * swapping an inverted latency range). The list reports every validation issue of the resulting configuration -- the
 * fixes made to the new value, and an exception class that cannot be used, whichever setter was called -- and is empty
 * when the resulting configuration is clean.
 * <p>
 * Part of the public API covered by semantic versioning. Instances are provided by Goblin: implementing this interface
 * is not supported, and methods may be added to it in a minor version.
 */
public interface MutableAssaultConfig extends AssaultConfig {

    /**
     * Upper bound accepted for any latency value: a larger delay would pin a worker thread for longer than any realistic
     * resilience scenario needs and can starve the worker pool.
     */
    long MAX_LATENCY_MS = 300_000;

    /**
     * Returns a read-only view frozen on the current state: every getter of the view reads the same state, whatever
     * changes happen meanwhile.
     *
     * @return a frozen copy of the current configuration
     */
    AssaultConfig snapshot();

    /**
     * Restores every value to its built-in default, not to {@code application.properties}: profile {@code NONE}, latency
     * assault on with 100-5000 ms, 503, truncate 50 %, level 100 %, layers {@code HTTP_IN} and {@code HTTP_OUT}, all
     * client-side assaults off. Published as a single change.
     *
     * @return the validation fixes, empty when the defaults were clean
     */
    List<String> resetToDefaults();

    /**
     * @param latencyEnabled whether the latency assault is enabled
     */
    void setLatencyEnabled(boolean latencyEnabled);

    /**
     * @param exceptionEnabled whether the exception assault is enabled
     */
    void setExceptionEnabled(boolean exceptionEnabled);

    /**
     * @param httpStatusEnabled whether the HTTP status assault is enabled
     */
    void setHttpStatusEnabled(boolean httpStatusEnabled);

    /**
     * @param dependencyDegradationEnabled whether the dependency degradation assault is enabled
     */
    void setDependencyDegradationEnabled(boolean dependencyDegradationEnabled);

    /**
     * @param clientLatencyEnabled whether the client-side latency assault is enabled
     */
    void setClientLatencyEnabled(boolean clientLatencyEnabled);

    /**
     * @param clientExceptionEnabled whether the client-side exception assault is enabled
     */
    void setClientExceptionEnabled(boolean clientExceptionEnabled);

    /**
     * @param responseBodyEnabled whether the response body assault is enabled
     */
    void setResponseBodyEnabled(boolean responseBodyEnabled);

    /**
     * @param responseHeaderEnabled whether the response header assault is enabled
     */
    void setResponseHeaderEnabled(boolean responseHeaderEnabled);

    /**
     * Switches the active profile and applies its defaults. A non-{@code NONE} profile turns every server-side assault
     * off, then enables its own assaults with their parameters; each toggle can then be overridden on top of it.
     * Client-side toggles and layers are never touched, and {@code NONE} leaves everything untouched. Published as a
     * single change.
     *
     * @param profile the profile to activate, or {@code null} for {@link AssaultProfile#NONE}
     * @return the effective active profile
     */
    AssaultProfile setProfile(AssaultProfile profile);

    /**
     * Sets the lower latency bound, clamped to {@code 0}-{@value #MAX_LATENCY_MS} ms. The bounds are not reordered, so
     * updating min then max in two calls never swaps an intermediate state; prefer {@link #setLatencyRange(long, long)}.
     *
     * @param latencyMinMs the lower bound in milliseconds
     */
    void setLatencyMinMs(long latencyMinMs);

    /**
     * Sets the upper latency bound, clamped to {@code 0}-{@value #MAX_LATENCY_MS} ms. See {@link #setLatencyMinMs(long)}.
     *
     * @param latencyMaxMs the upper bound in milliseconds
     */
    void setLatencyMaxMs(long latencyMaxMs);

    /**
     * Sets both latency bounds at once, then validates the range (clamping, and swapping an inverted pair).
     *
     * @param latencyMinMs the lower bound in milliseconds
     * @param latencyMaxMs the upper bound in milliseconds
     * @return the validation issues of the resulting configuration, empty when it is clean
     */
    List<String> setLatencyRange(long latencyMinMs, long latencyMaxMs);

    /**
     * Changes the exception class thrown by the exception assaults. The class must extend {@link RuntimeException} and
     * have a {@code String} constructor; otherwise the assault falls back to {@code RuntimeException} and the returned
     * list says why. A blank value is rejected and the current type is kept.
     *
     * @param exceptionType the fully qualified class name
     * @return the validation issues of the resulting configuration, empty when it is clean
     */
    List<String> setExceptionType(String exceptionType);

    /**
     * @param exceptionMessage the message of the thrown exception
     */
    void setExceptionMessage(String exceptionMessage);

    /**
     * Changes the status code returned by the HTTP status assault; a code outside 100-599 is replaced by 503.
     *
     * @param httpStatusCode the status code
     * @return the validation issues of the resulting configuration, empty when it is clean
     */
    List<String> setHttpStatusCode(int httpStatusCode);

    /**
     * @param httpStatusMessage the message of the HTTP status assault response
     */
    void setHttpStatusMessage(String httpStatusMessage);

    /**
     * Changes the response body transformation, re-validating the percentage for the new mode.
     *
     * @param responseBodyMode the transformation, or {@code null} for {@link ResponseBodyMode#TRUNCATE}
     */
    void setResponseBodyMode(ResponseBodyMode responseBodyMode);

    /**
     * Changes the target size of the transformed body, clamped to the range of the active mode.
     *
     * @param responseBodyPercentage the target size in percent
     * @return the validation issues of the resulting configuration, empty when it is clean
     */
    List<String> setResponseBodyPercentage(int responseBodyPercentage);

    /**
     * Adds or replaces the rule applied to the named response header. Header names are case-insensitive: a rule for the
     * same name in another casing is replaced, and the given casing is kept for the emitted header.
     *
     * @param name the header name, an HTTP token
     * @param action the action to apply
     * @param value the value written by {@code SET}; {@code null} emits a bare header
     * @throws IllegalArgumentException when the name is blank or not an HTTP token, the action is {@code null}, or the
     *         value contains characters that cannot be emitted in an HTTP header
     */
    void setResponseHeader(String name, ResponseHeaderAction action, String value);

    /**
     * Removes the rule of the named response header, in any casing.
     *
     * @param name the header name
     */
    void removeResponseHeader(String name);

    /**
     * Changes the percentage of requests, calls or messages subject to an assault, clamped to 0-100.
     *
     * @param targetLevel the target level in percent
     * @return the validation issues of the resulting configuration, empty when it is clean
     */
    List<String> setTargetLevel(int targetLevel);

    /**
     * Replaces the armed layers. A {@code null} or empty collection restores the default, {@code HTTP_IN} and
     * {@code HTTP_OUT}.
     *
     * @param layers the layers to arm
     */
    void setLayers(Collection<ChaosLayer> layers);

    /**
     * Arms or disarms a single layer, leaving the others untouched.
     *
     * @param layer the layer to toggle
     * @param enabled {@code true} to arm the layer
     */
    void setLayerEnabled(ChaosLayer layer, boolean enabled);
}
