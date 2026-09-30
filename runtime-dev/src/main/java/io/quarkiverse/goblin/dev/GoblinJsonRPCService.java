package io.quarkiverse.goblin.dev;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultProfile;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.DeactivationReason;
import io.quarkiverse.goblin.Enums;
import io.quarkiverse.goblin.MarkdownReportGenerator;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.ResponseBodyMode;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.quarkus.runtime.annotations.DevMCPEnableByDefault;
import io.quarkus.runtime.annotations.JsonRpcDescription;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class GoblinJsonRPCService {

    private static final Logger LOG = Logger.getLogger(GoblinJsonRPCService.class);

    /** Longest auto-off delay accepted from the Dev UI, matching {@link AssaultEngine#MAX_AUTO_OFF_MILLIS}. */
    static final int MAX_AUTO_OFF_MINUTES = 24 * 60;

    @Inject
    AssaultEngine engine;

    /**
     * Returns the current assault engine status for the Dev UI.
     *
     * @return a JSON object with the active flag, why chaos is off, the pending auto-off, profile, assault toggles, and
     *         target level
     */
    /**
     * Shared by the read-only tool descriptions: what the target level and the chaos layers mean, which an agent needs
     * to read the status and the configuration correctly.
     */
    static final String LEVEL_AND_LAYERS = "'level' is the percentage (0-100) of eligible requests an armed assault "
            + "affects. Each inbound request is assaulted on at most one armed layer, the deepest one whose level draw "
            + "passes (DATABASE, then MESSAGING, then SERVICE, then HTTP_IN): the latency and exception assaults fire on "
            + "any of them, the other server-side assaults (HTTP status, dependency degradation, response body, "
            + "response headers) on HTTP_IN only. The client-side toggles (clientLatencyEnabled, "
            + "clientExceptionEnabled) apply to outgoing REST Client and Vert.x WebClient calls when HTTP_OUT is armed, "
            + "each call drawing the level on its own; HTTP_OUT armed without a client-side toggle has no effect.";

    /**
     * Appended to the read-only tool descriptions: an agent only sees the enabled tools, and must not conclude that
     * Goblin cannot arm anything when the opt-in tools are simply not enabled yet.
     */
    static final String OPT_IN_TOOLS = " The tools that arm or change chaos exist but stay disabled, and absent "
            + "from the tool list, until the developer enables them in the Dev UI, Dev MCP tab: setActive(active), "
            + "toggleActive, setProfile(profile), applyConfig(config), resetDefaults, the toggles toggleLatency, "
            + "toggleException, toggleHttpStatus, toggleDependencyDegradation, toggleClientLatency, "
            + "toggleClientException, toggleResponseBody, toggleResponseHeader, the setters setLatencyRange(minMs, "
            + "maxMs), setExceptionConfig(type, message), setHttpStatusConfig(code, message), "
            + "setResponseBodyConfig(mode, percentage), setResponseHeaderInfo(name, action, value), "
            + "removeResponseHeader(name), setTargetLevel(level), and resetCounters and clearHistory. A call to one of "
            + "them fails with 'Method not found' until it is enabled: ask the developer to enable it.";

    @DevMCPEnableByDefault
    @JsonRpcDescription("Read a summary of the chaos state; use getConfig for the parameter values (latency range, "
            + "exception, HTTP status, body and header settings). Returns the master 'active' flag, 'inactiveReason' "
            + "when chaos is off (manual, auto-off, disabled, test-mode or launch-mode; absent while chaos is active), "
            + "'autoOffRemainingMs' (milliseconds left before the pending auto-off, 0 when none), the active profile, "
            + "every assault toggle, the armed chaos layers, the layers available in this application, and 'level'. "
            + LEVEL_AND_LAYERS + " Read-only: it never changes the configuration." + OPT_IN_TOOLS)
    public JsonObject getStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        // isActive() first: it applies an elapsed auto-off on the spot, so the reason is only the current one afterwards
        boolean active = engine.isActive();
        DeactivationReason inactiveReason = engine.inactiveReason();
        // typed as Object so the null case goes through put(String, Object); the Dev UI JSON-RPC transport leaves null
        // fields out, so the key is absent from the response while chaos is active
        Object reasonTag = inactiveReason != null ? inactiveReason.tag() : null;
        return new JsonObject()
                .put("active", active)
                .put("inactiveReason", reasonTag)
                .put("autoOffRemainingMs", autoOffRemainingMs())
                .put("profile", cfg != null ? cfg.getProfile().name() : "NONE")
                .put("layers", cfg != null ? layersJson(cfg) : new JsonArray())
                .put("availableLayers", availableLayersJson())
                .put("latencyEnabled", cfg != null && cfg.isLatencyEnabled())
                .put("exceptionEnabled", cfg != null && cfg.isExceptionEnabled())
                .put("httpStatusEnabled", cfg != null && cfg.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", cfg != null && cfg.isDependencyDegradationEnabled())
                .put("clientLatencyEnabled", cfg != null && cfg.isClientLatencyEnabled())
                .put("clientExceptionEnabled", cfg != null && cfg.isClientExceptionEnabled())
                .put("responseBodyEnabled", cfg != null && cfg.isResponseBodyEnabled())
                .put("responseHeaderEnabled", cfg != null && cfg.isResponseHeaderEnabled())
                .put("level", cfg != null ? cfg.getTargetLevel() : 100);
    }

    /**
     * Returns the full current assault configuration for the Dev UI.
     *
     * @return a JSON object with profile, toggles, and per-assault parameters, or an empty object if not initialised
     */
    @DevMCPEnableByDefault
    @JsonRpcDescription("Read the full assault configuration with every parameter value, in the same shape returned by "
            + "every mutating tool; use getStatus for a summary with the active flag and the auto-off. Returns the "
            + "profile, every toggle, the latency range, the exception type and message, the HTTP status code and "
            + "message, the response body mode and percentage, the response header rules, the armed and available "
            + "chaos layers, the target level, and 'exceptionPresets'. " + LEVEL_AND_LAYERS + " Read-only."
            + OPT_IN_TOOLS)
    public JsonObject getConfig() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return new JsonObject();
        }
        return configJson(cfg);
    }

    /**
     * Switches the active assault profile at runtime via the Dev UI.
     * <p>
     * The profile name is matched case-insensitively; a blank or {@code null} value resets to
     * {@link AssaultProfile#NONE}. Unknown names and an uninitialised engine are reported as a stable error object
     * ({@code ok=false}) instead of throwing.
     *
     * @param profile the profile name (case-insensitive), {@code null} or blank to reset to {@link AssaultProfile#NONE}
     * @return the full configuration JSON with an {@code ok} flag, or {@code ok=false} with an {@code error} message
     */
    @JsonRpcDescription("Switch the composite assault profile. Any profile other than NONE first turns every "
            + "server-side assault off (response body and response header included), then enables its own assaults "
            + "with its own parameters. The client-side toggles and the chaos layers are never touched, and every "
            + "toggle stays overridable afterwards. An unknown profile, or an engine that is not initialised yet, is "
            + "rejected with ok=false. Returns the full configuration plus ok.")
    public JsonObject setProfile(
            @JsonRpcDescription("Profile name: NONE, SLOW_FAILURE, INTERMITTENT or TIMEOUT (case-insensitive). "
                    + "A blank value resets to NONE.") String profile) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            LOG.warnf("Goblin: cannot switch profile '%s', engine is not initialised", profile);
            return new JsonObject().put("ok", false).put("error", "Engine is not initialised");
        }
        try {
            AssaultProfile previous = cfg.getProfile();
            AssaultProfile next = parseProfile(profile);
            cfg.setProfile(next);
            LOG.warnf("Goblin profile changed: %s -> %s", previous, cfg.getProfile());
            return configJson(cfg).put("ok", true);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Goblin: cannot switch profile '%s': %s", profile, e.getMessage());
            return new JsonObject().put("ok", false).put("error", e.getMessage());
        }
    }

    /**
     * Serialises the full mutable configuration into a {@link JsonObject} for the Dev UI.
     *
     * @param cfg the current mutable assault configuration
     * @return a JSON representation including profile, toggles, and per-assault parameters
     */
    private JsonObject configJson(MutableAssaultConfig cfg) {
        JsonObject latency = new JsonObject()
                .put("minMilliseconds", cfg.getLatencyMinMs())
                .put("maxMilliseconds", cfg.getLatencyMaxMs());

        JsonObject exception = new JsonObject()
                .put("type", cfg.getExceptionType())
                .put("message", cfg.getExceptionMessage());

        JsonObject httpStatus = new JsonObject()
                .put("code", cfg.getHttpStatusCode())
                .put("message", cfg.getHttpStatusMessage());

        JsonObject body = new JsonObject()
                .put("mode", cfg.getResponseBodyMode().name())
                .put("percentage", cfg.getResponseBodyPercentage());

        return new JsonObject()
                .put("profile", cfg.getProfile().name())
                .put("layers", layersJson(cfg))
                .put("availableLayers", availableLayersJson())
                .put("latencyEnabled", cfg.isLatencyEnabled())
                .put("exceptionEnabled", cfg.isExceptionEnabled())
                .put("httpStatusEnabled", cfg.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", cfg.isDependencyDegradationEnabled())
                .put("clientLatencyEnabled", cfg.isClientLatencyEnabled())
                .put("clientExceptionEnabled", cfg.isClientExceptionEnabled())
                .put("responseBodyEnabled", cfg.isResponseBodyEnabled())
                .put("responseHeaderEnabled", cfg.isResponseHeaderEnabled())
                .put("latency", latency)
                .put("exception", exception)
                .put("httpStatus", httpStatus)
                .put("body", body)
                .put("headers", headersJson(cfg))
                .put("level", cfg.getTargetLevel())
                .put("exceptionPresets", MutableAssaultConfig.EXCEPTION_PRESETS);
    }

    /**
     * Serialises the configured response header rules as a JSON object mapping each header name to an object holding the
     * {@code action} and {@code value}.
     *
     * @param cfg the current mutable assault configuration
     * @return the header rules keyed by header name
     */
    private static JsonObject headersJson(MutableAssaultConfig cfg) {
        JsonObject result = new JsonObject();
        cfg.getResponseHeaders().forEach(
                (name, rule) -> result.put(name,
                        new JsonObject().put("action", rule.action().name()).put("value", rule.value())));
        return result;
    }

    /**
     * Converts a raw profile string to its {@link AssaultProfile} constant.
     * <p>
     * Matching is case-insensitive and ignores surrounding whitespace; a {@code null} or blank value maps to
     * {@link AssaultProfile#NONE}.
     *
     * @param profile the raw string (may be {@code null} or blank)
     * @return the matching {@link AssaultProfile}, or {@link AssaultProfile#NONE} when blank
     * @throws IllegalArgumentException if the value does not match any {@link AssaultProfile}
     */
    private static AssaultProfile parseProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return AssaultProfile.NONE;
        }
        return Enums.parse(AssaultProfile.class, profile).orElseThrow(() -> new IllegalArgumentException(
                "Unknown assault profile '" + profile + "'. Valid values: NONE, SLOW_FAILURE, INTERMITTENT, TIMEOUT"));
    }

    @JsonRpcDescription("Flip the master chaos switch: activate chaos when it is off, deactivate it when it is on. "
            + "Activating arms every enabled assault; deactivating also cancels any pending auto-off. An auto-off that "
            + "has just elapsed leaves chaos off, so use setActive to state the intended state explicitly. Returns the "
            + "full configuration plus ok and the effective 'active' flag.")
    public JsonObject toggleActive() {
        boolean active = engine.toggleActive();
        LOG.warnf("Goblin chaos %s via Dev UI", active ? "ACTIVATED" : "DEACTIVATED");
        return activeResult(engine.getMutableConfig(), active);
    }

    @JsonRpcDescription("Set the master chaos switch to an explicit state, without the ambiguity of toggleActive when "
            + "an auto-off has just elapsed. Activating keeps a pending auto-off; deactivating cancels it. The decision "
            + "belongs to the dev session and survives a live reload in both directions, but it is never persisted: a "
            + "new process starts from quarkus.goblin.enabled again. Returns the full configuration plus ok and the "
            + "effective 'active' flag.")
    public JsonObject setActive(
            @JsonRpcDescription("true to arm chaos, false to disarm it and cancel any pending auto-off.") boolean active) {
        engine.setActive(active);
        LOG.warnf("Goblin chaos %s via Dev UI", active ? "ACTIVATED" : "DEACTIVATED");
        return activeResult(engine.getMutableConfig(), engine.isActive());
    }

    /**
     * Schedules chaos to switch itself off after the given number of minutes, replacing any pending auto-off. The engine
     * enforces the deadline on its own, whether or not the Dev UI is open.
     *
     * @param minutes the delay in minutes, between 1 and {@value #MAX_AUTO_OFF_MINUTES}
     * @return {@code ok} and the {@code autoOffRemainingMs}, or {@code ok=false} with an {@code error}
     */
    @DevMCPEnableByDefault
    @JsonRpcDescription("Safety net: schedule chaos to switch itself off after the given delay, replacing any pending "
            + "auto-off. It is accepted whether chaos is currently active or not, and the countdown starts at once: set "
            + "it before arming an experiment, since activating chaos keeps a pending auto-off. A delay outside 1-1440 "
            + "minutes is rejected with ok=false. The deadline is applied by the engine itself, whether or not any "
            + "client is connected, and a pending auto-off survives a live reload. Returns ok and the remaining time in "
            + "'autoOffRemainingMs'.")
    public JsonObject startAutoOff(@JsonRpcDescription("Delay in minutes, between 1 and 1440 (24 hours).") int minutes) {
        if (minutes <= 0 || minutes > MAX_AUTO_OFF_MINUTES) {
            return new JsonObject().put("ok", false)
                    .put("error", "Auto-off delay must be between 1 and " + MAX_AUTO_OFF_MINUTES + " minutes");
        }
        engine.scheduleAutoOff(minutes * 60_000L);
        LOG.warnf("Goblin chaos will auto-disable in %d min (Dev UI)", minutes);
        return new JsonObject().put("ok", true).put("autoOffRemainingMs", autoOffRemainingMs());
    }

    /**
     * Cancels any pending auto-off; chaos stays in its current state.
     *
     * @return {@code ok} and {@code autoOffRemainingMs=0}
     */
    @DevMCPEnableByDefault
    @JsonRpcDescription("Cancel a pending auto-off. Chaos stays in its current state. Returns ok and "
            + "'autoOffRemainingMs' set to 0.")
    public JsonObject cancelAutoOff() {
        engine.cancelAutoOff();
        return new JsonObject().put("ok", true).put("autoOffRemainingMs", 0L);
    }

    private long autoOffRemainingMs() {
        long deadline = engine.autoOffDeadline();
        return deadline > 0 ? Math.max(0, deadline - System.currentTimeMillis()) : 0;
    }

    /**
     * Builds the full-config mutation result for an active-state change, mirroring the single-source-of-truth contract of
     * every other mutation.
     *
     * @param cfg the current mutable configuration, possibly {@code null} while the engine is not yet initialised
     * @param active the effective chaos active flag after the change
     * @return the full configuration plus {@code ok} and {@code active} flags
     */
    private JsonObject activeResult(MutableAssaultConfig cfg, boolean active) {
        return (cfg != null ? configJson(cfg) : new JsonObject())
                .put("ok", true)
                .put("active", active);
    }

    @JsonRpcDescription("Flip the latency assault, which injects extra delay into eligible requests. This tool only "
            + "arms or disarms the assault; setLatencyRange changes its parameters. Returns the full configuration "
            + "plus ok.")
    public JsonObject toggleLatency() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(!cfg.isLatencyEnabled());
        LOG.warnf("Goblin latency %s via Dev UI", cfg.isLatencyEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    @JsonRpcDescription("Flip the exception assault, which makes eligible requests throw the configured exception "
            + "instead of returning. This tool only arms or disarms the assault; setExceptionConfig changes its "
            + "parameters. Returns the full configuration plus ok.")
    public JsonObject toggleException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(!cfg.isExceptionEnabled());
        LOG.warnf("Goblin exception %s via Dev UI", cfg.isExceptionEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    @JsonRpcDescription("Flip the HTTP status assault, which aborts eligible inbound requests on the HTTP_IN layer with "
            + "the configured status code and message before the endpoint runs: the business code is not executed. "
            + "This tool only arms or disarms the assault; setHttpStatusConfig changes its parameters. Returns the "
            + "full configuration plus ok.")
    public JsonObject toggleHttpStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(!cfg.isHttpStatusEnabled());
        LOG.warnf("Goblin HTTP status %s via Dev UI", cfg.isHttpStatusEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    @JsonRpcDescription("Flip the dependency degradation assault, which aborts eligible inbound requests on the HTTP_IN "
            + "layer with a 503 'Dependency unavailable (Goblin chaos)' before the endpoint runs, to simulate a "
            + "dependency that is down. It adds no delay and touches no datasource nor messaging channel: use the "
            + "latency or exception assaults on the DATABASE or MESSAGING layers for that. Returns the full "
            + "configuration plus ok.")
    public JsonObject toggleDependencyDegradation() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setDependencyDegradationEnabled(!cfg.isDependencyDegradationEnabled());
        LOG.warnf("Goblin dependency degradation %s via Dev UI", cfg.isDependencyDegradationEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    /**
     * Toggles the client-side latency assault, which sleeps on outgoing REST Client calls before they are dispatched.
     *
     * @return a JSON object with the {@code ok} flag and the new {@code clientLatencyEnabled} toggle value
     */
    @JsonRpcDescription("Flip the client-side latency assault, which adds extra delay to the outgoing REST Client and "
            + "Vert.x WebClient calls. Returns the full configuration plus ok.")
    public JsonObject toggleClientLatency() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(!cfg.isClientLatencyEnabled());
        LOG.warnf("Goblin client latency %s via Dev UI", cfg.isClientLatencyEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    /**
     * Toggles the client-side exception assault, which throws before outbound REST Client requests are dispatched.
     *
     * @return a JSON object with the {@code ok} flag and the new {@code clientExceptionEnabled} toggle value
     */
    @JsonRpcDescription("Flip the client-side exception assault, which makes the outgoing REST Client and Vert.x "
            + "WebClient calls throw before being dispatched. Returns the full configuration plus ok.")
    public JsonObject toggleClientException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(!cfg.isClientExceptionEnabled());
        LOG.warnf("Goblin client exception %s via Dev UI", cfg.isClientExceptionEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    /**
     * Toggles the response body assault, which truncates or inflates the entity returned by eligible endpoints.
     *
     * @return a JSON object with the {@code ok} flag and the new {@code responseBodyEnabled} toggle value
     */
    @JsonRpcDescription("Flip the response body assault, which truncates or inflates the entity returned by eligible "
            + "endpoints. This tool only arms or disarms the assault; setResponseBodyConfig changes its mode and size. "
            + "Returns the full configuration plus ok.")
    public JsonObject toggleResponseBody() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(!cfg.isResponseBodyEnabled());
        LOG.warnf("Goblin response body %s via Dev UI", cfg.isResponseBodyEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    /**
     * Toggles the response header injection assault, which applies the configured add/override/remove rules to the
     * emitted response.
     *
     * @return a JSON object with the {@code ok} flag and the new {@code responseHeaderEnabled} toggle value
     */
    @JsonRpcDescription("Flip the response header assault, which applies the configured SET and REMOVE rules to the "
            + "headers of emitted responses. This tool only arms or disarms the assault; setResponseHeaderInfo and "
            + "removeResponseHeader change its rules. Returns the full configuration plus ok.")
    public JsonObject toggleResponseHeader() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(!cfg.isResponseHeaderEnabled());
        LOG.warnf("Goblin response header %s via Dev UI", cfg.isResponseHeaderEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    /**
     * Adds or replaces the injection rule applied to the named response header at runtime via the Dev UI.
     * <p>
     * The action is matched case-insensitively ({@code SET} or {@code REMOVE}); unknown actions and blank header names
     * are rejected with a stable error object. {@code REMOVE} ignores the supplied value.
     *
     * @param name the header name, never blank
     * @param action the action (case-insensitive)
     * @param value the value written by {@code SET}
     * @return a JSON object with the {@code ok} flag, the effective rule, and any {@code error}
     */
    @JsonRpcDescription("Add or replace the rule applied to one response header. A blank or invalid name, an unknown "
            + "action and a value carrying CR, LF or control characters are all rejected with ok=false. This tool only "
            + "stores the rule; toggleResponseHeader arms the assault. Returns the full configuration plus ok and the "
            + "effective rule as 'name', 'action' and 'value'.")
    public JsonObject setResponseHeaderInfo(
            @JsonRpcDescription("Header name; must be a non-blank RFC 9110 token such as X-Goblin.") String name,
            @JsonRpcDescription("SET to write the value, or REMOVE to drop the header. Case-insensitive; the legacy "
                    + "ADD and OVERRIDE labels are accepted and mapped to SET.") String action,
            @JsonRpcDescription("Header value written by SET. Must not contain CR, LF or control characters. "
                    + "REMOVE ignores it.") String value) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        ResponseHeaderAction parsed = parseHeaderAction(action);
        if (parsed == null) {
            LOG.warnf("Goblin: response header rule rejected: name='%s' action='%s' value='%s': unknown action", name,
                    action, value);
            return new JsonObject().put("ok", false)
                    .put("error", "Unknown response header action '" + action + "'. Valid values: SET, REMOVE");
        }
        if (name == null || name.isBlank()) {
            LOG.warnf("Goblin: response header rule rejected: name='%s' action='%s': blank name", name, action);
            return new JsonObject().put("ok", false).put("error", "Response header name must not be blank");
        }
        String safeValue = value != null ? value : "";
        if (!MutableAssaultConfig.isValidResponseHeaderValue(safeValue)) {
            LOG.warnf("Goblin: response header rule rejected: name='%s' action='%s' value contains CR, LF or control "
                    + "characters", name, action);
            return new JsonObject().put("ok", false)
                    .put("error", "Response header value must not contain CR, LF or control characters");
        }
        String trimmed = name.trim();
        try {
            cfg.setResponseHeader(trimmed, parsed, safeValue);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Goblin: response header rule rejected: name='%s' action='%s': %s", trimmed, action, e.getMessage());
            return new JsonObject().put("ok", false).put("error", e.getMessage());
        }
        LOG.warnf("Goblin response header changed: %s %s value='%s'", trimmed, parsed, safeValue);
        return configJson(cfg)
                .put("ok", true)
                .put("name", trimmed)
                .put("action", parsed.name())
                .put("value", cfg.getResponseHeaders().get(trimmed).value());
    }

    /**
     * Removes the injection rule for the named response header at runtime via the Dev UI.
     *
     * @param name the header name, never blank
     * @return a JSON object with the {@code ok} flag, and any {@code error}
     */
    @JsonRpcDescription("Drop the configured rule for one response header. A blank name is rejected with ok=false. "
            + "Returns the full configuration plus ok.")
    public JsonObject removeResponseHeader(
            @JsonRpcDescription("Name of the header rule to drop; must not be blank.") String name) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (name == null || name.isBlank()) {
            return new JsonObject().put("ok", false).put("error", "Response header name must not be blank");
        }
        String trimmed = name.trim();
        cfg.removeResponseHeader(trimmed);
        LOG.warnf("Goblin response header removed: %s", trimmed);
        return configJson(cfg).put("ok", true);
    }

    /**
     * Serialises the armed layers for the Dev UI, in ascending declaration order.
     *
     * @return the armed layer names
     */
    private JsonArray availableLayersJson() {
        JsonArray layers = new JsonArray();
        engine.getAvailableLayers().forEach(layer -> layers.add(layer.name()));
        return layers;
    }

    private static JsonArray layersJson(MutableAssaultConfig cfg) {
        JsonArray layers = new JsonArray();
        cfg.getLayers().forEach(layer -> layers.add(layer.name()));
        return layers;
    }

    /**
     * Converts a raw response header action string to its {@link ResponseHeaderAction} constant, tolerating case and
     * surrounding whitespace. The historical {@code ADD} and {@code OVERRIDE} labels are mapped to
     * {@link ResponseHeaderAction#SET} so pre-existing saved profiles keep working.
     *
     * @param action the raw string (may be {@code null} or blank)
     * @return the matching {@link ResponseHeaderAction}, or {@code null} when blank or unknown
     */
    private static ResponseHeaderAction parseHeaderAction(String action) {
        return ResponseHeaderAction.parse(action).orElse(null);
    }

    /**
     * Changes the response body transformation mode and target size at runtime via the Dev UI.
     * <p>
     * The mode is matched case-insensitively ({@code TRUNCATE} or {@code INFLATE}); invalid modes and percentages are
     * rejected with a stable error object. The percentage is validated and clamped for the selected mode, with the
     * corrective warning surfaced through the {@code warning} field.
     *
     * @param mode the transformation mode (case-insensitive)
     * @param percentage the target size in percent of the original body
     * @return a JSON object with the {@code ok} flag, the effective {@code mode}/{@code percentage}, and any clamping
     *         {@code warning}
     */
    @JsonRpcDescription("Set the response body transformation. This tool only changes the parameters; "
            + "toggleResponseBody arms the assault. An unknown mode is rejected with ok=false, while an out-of-range "
            + "percentage is clamped with the correction reported in 'warning'. Returns the full configuration plus "
            + "ok, the effective 'mode' and 'percentage', and any clamping warning.")
    public JsonObject setResponseBodyConfig(
            @JsonRpcDescription("Transformation mode: TRUNCATE to cut the entity, INFLATE to grow it "
                    + "(case-insensitive).") String mode,
            @JsonRpcDescription("Target body size as a percentage of the original: 0-100 for TRUNCATE (the share of "
                    + "the body kept), 101-1000 for INFLATE (the padded size). A value outside the range of the chosen "
                    + "mode is clamped into it.") int percentage) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        ResponseBodyMode parsed = parseBodyMode(mode);
        if (parsed == null) {
            return new JsonObject().put("ok", false)
                    .put("error", "Unknown response body mode '" + mode + "'. Valid values: TRUNCATE, INFLATE");
        }
        String previous = cfg.getResponseBodyMode().name();
        int previousPercentage = cfg.getResponseBodyPercentage();
        cfg.setResponseBodyMode(parsed);
        List<String> issues = cfg.setResponseBodyPercentage(percentage);
        LOG.warnf("Goblin response body changed: %s %d%% -> %s %d%%", previous, previousPercentage,
                cfg.getResponseBodyMode(), cfg.getResponseBodyPercentage());
        return configJson(cfg)
                .put("ok", true)
                .put("mode", cfg.getResponseBodyMode().name())
                .put("percentage", cfg.getResponseBodyPercentage())
                .put("warning", toWarning(issues));
    }

    /**
     * Converts a raw response body mode string to its {@link ResponseBodyMode} constant, tolerating case and
     * surrounding whitespace.
     *
     * @param mode the raw string (may be {@code null} or blank)
     * @return the matching {@link ResponseBodyMode}, or {@code null} when blank or unknown
     */
    private static ResponseBodyMode parseBodyMode(String mode) {
        return Enums.parse(ResponseBodyMode.class, mode).orElse(null);
    }

    @JsonRpcDescription("Set the latency range injected by the latency assault. This tool only changes the "
            + "parameters; toggleLatency arms the assault. The values are validated and clamped with the correction "
            + "reported in 'warning'. Returns the full configuration plus ok, the effective 'minMilliseconds' and "
            + "'maxMilliseconds', and any clamping warning.")
    public JsonObject setLatencyRange(
            @JsonRpcDescription("Minimum injected delay, in milliseconds.") long minMs,
            @JsonRpcDescription("Maximum injected delay, in milliseconds.") long maxMs) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        long prevMin = cfg.getLatencyMinMs();
        long prevMax = cfg.getLatencyMaxMs();
        List<String> issues = cfg.setLatencyRange(minMs, maxMs);
        LOG.warnf("Goblin latency changed: %d-%d ms -> %d-%d ms", prevMin, prevMax,
                cfg.getLatencyMinMs(), cfg.getLatencyMaxMs());
        return configJson(cfg)
                .put("ok", true)
                .put("minMilliseconds", cfg.getLatencyMinMs())
                .put("maxMilliseconds", cfg.getLatencyMaxMs())
                .put("warning", toWarning(issues));
    }

    @JsonRpcDescription("Set the exception thrown by the exception assault. This tool only changes the parameters; "
            + "toggleException arms the assault. A class that is not loadable, does not extend RuntimeException or "
            + "lacks a single-String constructor is kept but flagged in 'warning', because the engine then falls back "
            + "to RuntimeException. Returns the full configuration plus ok, the effective 'type' and 'message', and any "
            + "warning.")
    public JsonObject setExceptionConfig(
            @JsonRpcDescription("Exception class name; must be loadable, extend RuntimeException and expose a "
                    + "single-String constructor.") String type,
            @JsonRpcDescription("Message carried by the thrown exception.") String message) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        String prevType = cfg.getExceptionType();
        List<String> issues = cfg.setExceptionType(type);
        if (!prevType.equals(cfg.getExceptionType())) {
            LOG.warnf("Goblin exception changed: %s -> %s", prevType, cfg.getExceptionType());
        }
        cfg.setExceptionMessage(message);
        return configJson(cfg)
                .put("ok", true)
                .put("type", cfg.getExceptionType())
                .put("message", cfg.getExceptionMessage())
                .put("warning", toWarning(issues));
    }

    @JsonRpcDescription("Set the HTTP status assault parameters. This tool only changes the parameters; "
            + "toggleHttpStatus arms the assault. A code outside 100-599 is replaced by 503, with the correction "
            + "reported in 'warning'. Returns the full configuration plus ok, the effective 'code' and 'message', and "
            + "any warning.")
    public JsonObject setHttpStatusConfig(
            @JsonRpcDescription("HTTP status code of the aborted requests, 100-599; a value outside that range is "
                    + "replaced by 503.") int code,
            @JsonRpcDescription("Response body returned together with the status code.") String message) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        int prevCode = cfg.getHttpStatusCode();
        List<String> issues = cfg.setHttpStatusCode(code);
        cfg.setHttpStatusMessage(message);
        LOG.warnf("Goblin HTTP status changed: %d -> %d", prevCode, cfg.getHttpStatusCode());
        return configJson(cfg)
                .put("ok", true)
                .put("code", cfg.getHttpStatusCode())
                .put("message", cfg.getHttpStatusMessage())
                .put("warning", toWarning(issues));
    }

    @JsonRpcDescription("Set the percentage of eligible requests that the armed assaults affect, so a single value "
            + "widens or narrows every assault at once. Values outside 0-100 are clamped with the correction reported "
            + "in 'warning'. Returns the full configuration plus ok, the effective 'level', and any clamping warning.")
    public JsonObject setTargetLevel(
            @JsonRpcDescription("Percentage of eligible requests to affect, 0-100; 100 affects every eligible "
                    + "request.") int level) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        int previous = cfg.getTargetLevel();
        List<String> issues = cfg.setTargetLevel(level);
        LOG.warnf("Goblin target level changed: %d%% -> %d%%", previous, cfg.getTargetLevel());
        return configJson(cfg)
                .put("ok", true)
                .put("level", cfg.getTargetLevel())
                .put("warning", toWarning(issues));
    }

    private static String toWarning(List<String> issues) {
        return issues.stream().collect(Collectors.joining(" "));
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Read the assault history buffer, oldest entry first. It keeps the last 1000 assaults: older "
            + "entries are dropped as new ones arrive. Each entry carries the method, the "
            + "assault type, the epoch timestamp in milliseconds, the injected latency in milliseconds (0 for a "
            + "non-latency assault), the "
            + "source (server, service, rest-client, webclient, database, messaging) and a snapshot of the "
            + "configuration active at the time. Read-only.")
    public JsonArray getHistory() {
        JsonArray history = new JsonArray();
        for (AssaultEngine.AssaultRecord record : engine.getHistory()) {
            history.add(new JsonObject()
                    .put("method", record.method())
                    .put("type", record.type())
                    .put("timestamp", record.timestamp())
                    .put("latencyMs", record.latencyMs())
                    .put("source", record.sourceTag())
                    .put("config", record.configSnapshot()));
        }
        return history;
    }

    /**
     * Disables chaos immediately: stops the engine and switches every assault toggle off via the Dev UI kill switch.
     *
     * @return a JSON object with the {@code ok} flag, {@code active=false}, and the full configuration
     */
    @DevMCPEnableByDefault
    @JsonRpcDescription("Kill switch: disarm chaos, cancel any pending auto-off, turn every server-side and "
            + "client-side assault off and reset the profile to NONE. The armed layers, the target level and every "
            + "parameter value (latency range, messages, header rules) are kept, so re-arming an assault later reuses "
            + "them. It needs no prior configuration, so it is the safe way to stop everything whatever is currently "
            + "armed. Returns ok, 'active' set to false, and the full configuration.")
    public JsonObject disableAll() {
        engine.setActive(false);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg != null) {
            cfg.setLatencyEnabled(false);
            cfg.setExceptionEnabled(false);
            cfg.setHttpStatusEnabled(false);
            cfg.setDependencyDegradationEnabled(false);
            cfg.setClientLatencyEnabled(false);
            cfg.setClientExceptionEnabled(false);
            cfg.setResponseBodyEnabled(false);
            cfg.setResponseHeaderEnabled(false);
            cfg.setProfile(AssaultProfile.NONE);
        }
        LOG.warnf("Goblin: all assaults disabled via Dev UI kill switch");
        JsonObject result = cfg != null ? configJson(cfg) : new JsonObject();
        return result.put("ok", true).put("active", false);
    }

    /**
     * Restores every assault parameter to its built-in default (not to application.properties), keeping the engine's
     * active flag and any pending auto-off unchanged.
     *
     * @return a JSON object with the {@code ok} flag, any clamping {@code warning}, and the full reset configuration
     */
    @JsonRpcDescription("Restore every assault toggle and parameter to its built-in default, not to "
            + "application.properties: profile NONE, the latency assault on with 100-5000 ms, every other assault and "
            + "client-side assault off, HTTP status 503, body TRUNCATE 50%, no header rule, layers HTTP_IN and HTTP_OUT, "
            + "level 100. The master active flag and any pending auto-off are left unchanged, so this can still leave "
            + "chaos armed. Returns the full configuration plus ok and any clamping warning.")
    public JsonObject resetDefaults() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return new JsonObject().put("ok", false).put("error", "Engine is not initialised");
        }
        List<String> issues = cfg.resetToDefaults();
        LOG.warnf("Goblin: configuration reset to defaults via Dev UI");
        return configJson(cfg).put("ok", true).put("warning", toWarning(issues));
    }

    /**
     * Applies a (possibly partial) configuration object, used by the Dev UI import and saved custom profiles.
     * <p>
     * Every field present in {@code config} is applied; missing fields keep their current value. The profile, when
     * present, is applied first so its defaults can then be explicitly overridden by the remaining fields.
     * <p>
     * The parameter is a {@link Map} rather than a {@code JsonObject}: the Dev UI JSON-RPC codec deserializes
     * parameters through Jackson's bean conversion, which cannot bind arbitrary keys onto a {@code JsonObject}. The
     * incoming map is normalized recursively into a {@code JsonObject} so nested maps -- which Jackson returns as plain
     * {@link Map} instances -- are exposed as JSON objects regardless of the transport's deserialization strategy.
     *
     * @param config the configuration fields to apply, never {@code null}
     * @return a JSON object with the {@code ok} flag, any clamping {@code warning}, and the full effective configuration
     */
    @JsonRpcDescription("Apply a partial configuration in one call: every key present is applied and every omitted key "
            + "keeps its current value. Accepted keys: 'profile' (NONE, SLOW_FAILURE, INTERMITTENT, TIMEOUT); the "
            + "boolean toggles 'latencyEnabled', 'exceptionEnabled', 'httpStatusEnabled', "
            + "'dependencyDegradationEnabled', 'clientLatencyEnabled', 'clientExceptionEnabled', "
            + "'responseBodyEnabled', 'responseHeaderEnabled'; 'latency' {minMilliseconds, maxMilliseconds}; "
            + "'exception' {type, message}; 'httpStatus' {code, message}; 'body' {mode, percentage} with mode TRUNCATE "
            + "or INFLATE; 'headers', a map of header name to {action, value} with action SET or REMOVE, which "
            + "REPLACES the whole rule set; 'layers', an array of layer names (SERVICE, HTTP_OUT, HTTP_IN, plus "
            + "DATABASE and MESSAGING when installed) which REPLACES the armed layers, an empty array restoring the "
            + "defaults; and 'level' (0-100). The profile is applied first so its defaults can then be overridden by "
            + "the other keys. Booleans and numbers given as strings are converted; an unconvertible value, an unknown "
            + "layer, an unknown action or an invalid header name is skipped and reported in 'warning'. A rejected "
            + "payload, an unknown profile for instance, applies nothing at all. Returns the full configuration plus "
            + "ok, and any warning.")
    public JsonObject applyConfig(
            @JsonRpcDescription("Partial configuration object. The accepted keys and their values are listed in this "
                    + "tool's description; an omitted key keeps its current value.") Map<String, Object> config) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return new JsonObject().put("ok", false).put("error", "Engine is not initialised");
        }
        if (config == null) {
            return new JsonObject().put("ok", false).put("error", "Missing configuration payload");
        }
        // staged on a working copy and published at once: an invalid payload never leaves a half-applied configuration
        MutableAssaultConfig staged = cfg.workingCopy();
        List<String> issues;
        try {
            issues = applyConfigTo(staged, toJsonObject(config));
        } catch (RuntimeException e) {
            LOG.warnf("Goblin: configuration rejected, nothing applied: %s", e.getMessage());
            return configJson(cfg).put("ok", false).put("error", "Invalid configuration, nothing applied: " + e.getMessage());
        }
        cfg.replaceWith(staged);
        LOG.warnf("Goblin: configuration applied via Dev UI: fields=%s", new ArrayList<>(config.keySet()));
        if (!issues.isEmpty()) {
            LOG.warnf("Goblin: configuration applied with issues: %s", issues);
        }
        return configJson(cfg).put("ok", true).put("warning", toWarning(issues));
    }

    /**
     * Recursively converts a map coming from the JSON-RPC codec into a {@code JsonObject}, turning every nested
     * {@link Map} (which the Jackson-based codec produces for JSON objects) into a {@code JsonObject} and normalizing
     * lists. This keeps nested structures such as the response header rules usable through {@code getJsonObject}.
     *
     * @param map the incoming map
     * @return the equivalent {@code JsonObject} with nested objects normalized
     */
    private static JsonObject toJsonObject(Map<String, Object> map) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return result;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                object.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeValue(item));
            }
            return normalized;
        }
        return value;
    }

    private static List<String> applyConfigTo(MutableAssaultConfig cfg, JsonObject config) {
        List<String> issues = new ArrayList<>();
        String profile = config.getString("profile");
        if (profile != null) {
            cfg.setProfile(parseProfile(profile));
        }
        if (config.containsKey("latencyEnabled")) {
            readBoolean(config, "latencyEnabled", issues).ifPresent(cfg::setLatencyEnabled);
        }
        if (config.containsKey("exceptionEnabled")) {
            readBoolean(config, "exceptionEnabled", issues).ifPresent(cfg::setExceptionEnabled);
        }
        if (config.containsKey("httpStatusEnabled")) {
            readBoolean(config, "httpStatusEnabled", issues).ifPresent(cfg::setHttpStatusEnabled);
        }
        if (config.containsKey("dependencyDegradationEnabled")) {
            readBoolean(config, "dependencyDegradationEnabled", issues).ifPresent(cfg::setDependencyDegradationEnabled);
        }
        if (config.containsKey("clientLatencyEnabled")) {
            readBoolean(config, "clientLatencyEnabled", issues).ifPresent(cfg::setClientLatencyEnabled);
        }
        if (config.containsKey("clientExceptionEnabled")) {
            readBoolean(config, "clientExceptionEnabled", issues).ifPresent(cfg::setClientExceptionEnabled);
        }
        if (config.containsKey("responseBodyEnabled")) {
            readBoolean(config, "responseBodyEnabled", issues).ifPresent(cfg::setResponseBodyEnabled);
        }
        if (config.containsKey("responseHeaderEnabled")) {
            readBoolean(config, "responseHeaderEnabled", issues).ifPresent(cfg::setResponseHeaderEnabled);
        }
        JsonObject latency = config.getJsonObject("latency");
        if (latency != null && latency.containsKey("minMilliseconds") && latency.containsKey("maxMilliseconds")) {
            Optional<Long> min = readLong(latency, "minMilliseconds", issues);
            Optional<Long> max = readLong(latency, "maxMilliseconds", issues);
            if (min.isPresent() && max.isPresent()) {
                issues.addAll(cfg.setLatencyRange(min.get(), max.get()));
            }
        }
        JsonObject exception = config.getJsonObject("exception");
        if (exception != null) {
            if (exception.containsKey("type")) {
                issues.addAll(cfg.setExceptionType(exception.getString("type")));
            }
            if (exception.containsKey("message")) {
                cfg.setExceptionMessage(exception.getString("message"));
            }
        }
        JsonObject httpStatus = config.getJsonObject("httpStatus");
        if (httpStatus != null) {
            if (httpStatus.containsKey("code")) {
                readLong(httpStatus, "code", issues).ifPresent(code -> issues.addAll(cfg.setHttpStatusCode(code.intValue())));
            }
            if (httpStatus.containsKey("message")) {
                cfg.setHttpStatusMessage(httpStatus.getString("message"));
            }
        }
        JsonObject body = config.getJsonObject("body");
        if (body != null) {
            if (body.containsKey("mode")) {
                ResponseBodyMode mode = parseBodyMode(body.getString("mode"));
                if (mode != null) {
                    cfg.setResponseBodyMode(mode);
                }
            }
            if (body.containsKey("percentage")) {
                readLong(body, "percentage", issues)
                        .ifPresent(percentage -> issues.addAll(cfg.setResponseBodyPercentage(percentage.intValue())));
            }
        }
        JsonObject headers = config.getJsonObject("headers");
        if (headers != null) {
            applyConfigHeaders(cfg, headers, issues);
        }
        applyConfigLayers(cfg, config.getValue("layers"), issues);
        if (config.containsKey("level")) {
            readLong(config, "level", issues).ifPresent(level -> issues.addAll(cfg.setTargetLevel(level.intValue())));
        }
        return issues;
    }

    /**
     * Replaces the armed layer set from an {@code applyConfig} payload. The value may arrive as a JSON array or as a
     * plain list depending on the transport's deserialization strategy. Unknown labels are skipped and reported as an
     * issue; an empty array restores the default layers.
     *
     * @param cfg the configuration to populate
     * @param raw the {@code layers} value, or {@code null} when absent
     * @param issues collecting validation warnings
     */
    private static void applyConfigLayers(MutableAssaultConfig cfg, Object raw, List<String> issues) {
        if (raw == null) {
            return;
        }
        List<?> items;
        if (raw instanceof List<?> list) {
            items = list;
        } else if (raw instanceof JsonArray array) {
            items = array.getList();
        } else {
            issues.add("Chaos layers must be provided as an array of layer names");
            LOG.warnf("Goblin: skipping layers from applyConfig: not an array (%s)", raw.getClass().getSimpleName());
            return;
        }
        Set<ChaosLayer> parsed = new LinkedHashSet<>();
        for (Object item : items) {
            if (item instanceof String name) {
                Enums.parse(ChaosLayer.class, name).ifPresentOrElse(parsed::add, () -> {
                    issues.add("Unknown chaos layer '" + name + "'");
                    LOG.warnf("Goblin: skipping layer '%s' from applyConfig", name);
                });
            }
        }
        cfg.setLayers(parsed);
    }

    /**
     * Replaces the configured response header rules with those declared in the payload. Unknown actions are skipped and
     * reported as an issue; every other pre-existing rule is dropped, matching the full-replacement semantics of the
     * {@code headers} object.
     *
     * @param cfg the configuration to populate
     * @param headers the header map from the payload
     * @param issues collecting validation warnings
     */
    /**
     * Reads a boolean field tolerantly: a JSON boolean, or the strings {@code "true"} / {@code "false"}. Any other value
     * is reported as an issue and the field is skipped.
     */
    private static Optional<Boolean> readBoolean(JsonObject json, String key, List<String> issues) {
        Object value = json.getValue(key);
        if (value instanceof Boolean flag) {
            return Optional.of(flag);
        }
        if (value instanceof String text && ("true".equalsIgnoreCase(text.trim()) || "false".equalsIgnoreCase(text.trim()))) {
            return Optional.of(Boolean.parseBoolean(text.trim()));
        }
        issues.add("'" + key + "' must be a boolean, got '" + value + "': skipped");
        return Optional.empty();
    }

    /**
     * Reads an integral field tolerantly: a JSON number, or a numeric string. Any other value is reported as an issue
     * and the field is skipped.
     */
    private static Optional<Long> readLong(JsonObject json, String key, List<String> issues) {
        Object value = json.getValue(key);
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Optional.of(Long.parseLong(text.trim()));
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        issues.add("'" + key + "' must be a number, got '" + value + "': skipped");
        return Optional.empty();
    }

    private static void applyConfigHeaders(MutableAssaultConfig cfg, JsonObject headers, List<String> issues) {
        cfg.getResponseHeaders().keySet().forEach(cfg::removeResponseHeader);
        for (Map.Entry<String, Object> entry : headers) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                issues.add("Response header name must not be blank");
                LOG.warnf("Goblin: skipping response header rule from applyConfig: blank name");
                continue;
            }
            JsonObject rule = headers.getJsonObject(name);
            if (rule == null) {
                issues.add("Response header '" + name + "' must carry an action and value");
                LOG.warnf("Goblin: skipping response header rule '%s' from applyConfig: missing action/value object", name);
                continue;
            }
            ResponseHeaderAction action = parseHeaderAction(rule.getString("action"));
            if (action == null) {
                issues.add("Unknown response header action '" + rule.getString("action") + "' for header '" + name + "'");
                LOG.warnf("Goblin: skipping response header rule '%s' from applyConfig: unknown action '%s'", name,
                        rule.getString("action"));
                continue;
            }
            String value = rule.getString("value");
            String safeValue = value != null ? value : "";
            if (!MutableAssaultConfig.isValidResponseHeaderValue(safeValue)) {
                issues.add("Response header '" + name
                        + "' value contains characters that cannot be emitted in an HTTP header");
                LOG.warnf("Goblin: skipping response header rule '%s' from applyConfig: value contains CR, LF or control "
                        + "characters", name);
                continue;
            }
            try {
                cfg.setResponseHeader(name, action, safeValue);
            } catch (IllegalArgumentException e) {
                issues.add(e.getMessage());
                LOG.warnf("Goblin: skipping response header rule '%s' from applyConfig: %s", name, e.getMessage());
                continue;
            }
            LOG.warnf("Goblin: response header rule applied via applyConfig: %s %s value='%s'", name, action, safeValue);
        }
    }

    /**
     * Returns the live assault counters since the engine started or counters were last reset.
     *
     * @return a JSON object with the {@code total} count, the {@code since} epoch timestamp, and the {@code byType} map
     */
    @DevMCPEnableByDefault
    @JsonRpcDescription("Read the assault counters for the current session. Returns 'total' assaults, the 'since' "
            + "epoch timestamp in milliseconds of the counting window, which starts when the application starts or "
            + "live-reloads, or at the last resetCounters, a 'byType' breakdown and a 'bySource' breakdown (server, "
            + "service, rest-client, webclient, database, messaging). Read-only; zeroing them takes resetCounters, an "
            + "opt-in tool the developer enables in the Dev UI.")
    public JsonObject getCounters() {
        JsonObject byType = new JsonObject();
        engine.getAssaultCounts().forEach(byType::put);
        JsonObject bySource = new JsonObject();
        engine.getAssaultCountsBySource().forEach(bySource::put);
        return new JsonObject()
                .put("total", engine.getTotalAssaultCount())
                .put("since", engine.getCountersSinceEpoch())
                .put("byType", byType)
                .put("bySource", bySource);
    }

    /**
     * Resets all assault counters to zero via the Dev UI.
     *
     * @return a JSON object with the {@code ok} flag
     */
    @JsonRpcDescription("Reset the assault counters to zero, discarding the counting window. This destroys the "
            + "evidence of what has been observed so far, so read getCounters first when it matters. Returns ok.")
    public JsonObject resetCounters() {
        engine.resetCounters();
        LOG.info("Goblin assault counters reset via Dev UI");
        return new JsonObject().put("ok", true);
    }

    @JsonRpcDescription("Empty the assault history buffer, discarding every recorded assault. This destroys the "
            + "evidence of what has been observed so far, so read getHistory first when it matters. Returns "
            + "'cleared' set to true.")
    public JsonObject clearHistory() {
        engine.clearHistory();
        LOG.info("Goblin assault history cleared via Dev UI");
        return new JsonObject()
                .put("cleared", true);
    }

    @DevMCPEnableByDefault
    @JsonRpcDescription("Render a factual Markdown report of the current configuration and of the assault history, "
            + "ready to paste into an issue or a pull request. Returns the 'markdown' text and the 'generatedAt' epoch "
            + "timestamp. Read-only: rendering the report changes no counter and no history entry.")
    public JsonObject getMarkdownReport() {
        String report = MarkdownReportGenerator.build(
                engine.isActive(),
                engine.getMutableConfig(),
                engine.getHistory());
        return new JsonObject()
                .put("markdown", report)
                .put("generatedAt", System.currentTimeMillis());
    }
}
