package io.quarkiverse.goblin.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultProfile;
import io.quarkiverse.goblin.MarkdownReportGenerator;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.ResponseBodyMode;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class GoblinJsonRPCService {

    private static final Logger LOG = Logger.getLogger(GoblinJsonRPCService.class);

    @Inject
    AssaultEngine engine;

    /**
     * Returns the current assault engine status for the Dev UI.
     *
     * @return a JSON object with the active flag, profile, assault toggles, and target level
     */
    public JsonObject getStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        return new JsonObject()
                .put("active", engine.isActive())
                .put("profile", cfg != null ? cfg.getProfile().name() : "NONE")
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
    public JsonObject setProfile(String profile) {
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
    private static JsonObject configJson(MutableAssaultConfig cfg) {
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
        String normalized = profile.trim().toUpperCase();
        for (AssaultProfile candidate : AssaultProfile.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown assault profile '" + profile + "'. Valid values: NONE, SLOW_FAILURE, INTERMITTENT, TIMEOUT");
    }

    public JsonObject toggleActive() {
        engine.setActive(!engine.isActive());
        LOG.warnf("Goblin chaos %s via Dev UI", engine.isActive() ? "ACTIVATED" : "DEACTIVATED");
        return activeResult(engine.getMutableConfig(), engine.isActive());
    }

    public JsonObject setActive(boolean active) {
        engine.setActive(active);
        LOG.warnf("Goblin chaos %s via Dev UI", active ? "ACTIVATED" : "DEACTIVATED");
        return activeResult(engine.getMutableConfig(), engine.isActive());
    }

    /**
     * Builds the full-config mutation result for an active-state change, mirroring the single-source-of-truth contract of
     * every other mutation.
     *
     * @param cfg the current mutable configuration, possibly {@code null} while the engine is not yet initialised
     * @param active the effective chaos active flag after the change
     * @return the full configuration plus {@code ok} and {@code active} flags
     */
    private static JsonObject activeResult(MutableAssaultConfig cfg, boolean active) {
        return (cfg != null ? configJson(cfg) : new JsonObject())
                .put("ok", true)
                .put("active", active);
    }

    public JsonObject toggleLatency() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(!cfg.isLatencyEnabled());
        LOG.warnf("Goblin latency %s via Dev UI", cfg.isLatencyEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    public JsonObject toggleException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(!cfg.isExceptionEnabled());
        LOG.warnf("Goblin exception %s via Dev UI", cfg.isExceptionEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

    public JsonObject toggleHttpStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(!cfg.isHttpStatusEnabled());
        LOG.warnf("Goblin HTTP status %s via Dev UI", cfg.isHttpStatusEnabled() ? "ENABLED" : "DISABLED");
        return configJson(cfg).put("ok", true);
    }

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
    public JsonObject setResponseHeaderInfo(String name, String action, String value) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        ResponseHeaderAction parsed = parseHeaderAction(action);
        if (parsed == null) {
            return new JsonObject().put("ok", false)
                    .put("error", "Unknown response header action '" + action + "'. Valid values: SET, REMOVE");
        }
        if (name == null || name.isBlank()) {
            return new JsonObject().put("ok", false).put("error", "Response header name must not be blank");
        }
        String safeValue = value != null ? value : "";
        if (!MutableAssaultConfig.isValidResponseHeaderValue(safeValue)) {
            return new JsonObject().put("ok", false)
                    .put("error", "Response header value must not contain CR, LF or control characters");
        }
        String trimmed = name.trim();
        cfg.setResponseHeader(trimmed, parsed, safeValue);
        LOG.warnf("Goblin response header changed: %s %s", trimmed, parsed);
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
    public JsonObject removeResponseHeader(String name) {
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
     * Converts a raw response header action string to its {@link ResponseHeaderAction} constant, tolerating case and
     * surrounding whitespace. The historical {@code ADD} and {@code OVERRIDE} labels are mapped to
     * {@link ResponseHeaderAction#SET} so pre-existing saved profiles keep working.
     *
     * @param action the raw string (may be {@code null} or blank)
     * @return the matching {@link ResponseHeaderAction}, or {@code null} when blank or unknown
     */
    private static ResponseHeaderAction parseHeaderAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        String normalized = action.trim().toUpperCase();
        if ("ADD".equals(normalized) || "OVERRIDE".equals(normalized)) {
            return ResponseHeaderAction.SET;
        }
        for (ResponseHeaderAction candidate : ResponseHeaderAction.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
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
    public JsonObject setResponseBodyConfig(String mode, int percentage) {
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
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalized = mode.trim().toUpperCase();
        for (ResponseBodyMode candidate : ResponseBodyMode.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    public JsonObject setLatencyRange(long minMs, long maxMs) {
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

    public JsonObject setExceptionConfig(String type, String message) {
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

    public JsonObject setHttpStatusConfig(int code, String message) {
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

    public JsonObject setTargetLevel(int level) {
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

    public JsonArray getHistory() {
        JsonArray history = new JsonArray();
        for (AssaultEngine.AssaultRecord record : engine.getHistory()) {
            history.add(new JsonObject()
                    .put("method", record.method())
                    .put("type", record.type())
                    .put("timestamp", record.timestamp())
                    .put("latencyMs", record.latencyMs())
                    .put("config", record.configSnapshot()));
        }
        return history;
    }

    /**
     * Disables chaos immediately: stops the engine and switches every assault toggle off via the Dev UI kill switch.
     *
     * @return a JSON object with the {@code ok} flag, {@code active=false}, and the full configuration
     */
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
     * Restores every assault parameter to its application.properties default, keeping the engine's active flag unchanged.
     *
     * @return a JSON object with the {@code ok} flag, any clamping {@code warning}, and the full reset configuration
     */
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
    public JsonObject applyConfig(Map<String, Object> config) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return new JsonObject().put("ok", false).put("error", "Engine is not initialised");
        }
        if (config == null) {
            return new JsonObject().put("ok", false).put("error", "Missing configuration payload");
        }
        List<String> issues = applyConfigTo(cfg, toJsonObject(config));
        LOG.warnf("Goblin: configuration applied via Dev UI");
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
            cfg.setLatencyEnabled(config.getBoolean("latencyEnabled"));
        }
        if (config.containsKey("exceptionEnabled")) {
            cfg.setExceptionEnabled(config.getBoolean("exceptionEnabled"));
        }
        if (config.containsKey("httpStatusEnabled")) {
            cfg.setHttpStatusEnabled(config.getBoolean("httpStatusEnabled"));
        }
        if (config.containsKey("dependencyDegradationEnabled")) {
            cfg.setDependencyDegradationEnabled(config.getBoolean("dependencyDegradationEnabled"));
        }
        if (config.containsKey("clientLatencyEnabled")) {
            cfg.setClientLatencyEnabled(config.getBoolean("clientLatencyEnabled"));
        }
        if (config.containsKey("clientExceptionEnabled")) {
            cfg.setClientExceptionEnabled(config.getBoolean("clientExceptionEnabled"));
        }
        if (config.containsKey("responseBodyEnabled")) {
            cfg.setResponseBodyEnabled(config.getBoolean("responseBodyEnabled"));
        }
        if (config.containsKey("responseHeaderEnabled")) {
            cfg.setResponseHeaderEnabled(config.getBoolean("responseHeaderEnabled"));
        }
        JsonObject latency = config.getJsonObject("latency");
        if (latency != null && latency.containsKey("minMilliseconds") && latency.containsKey("maxMilliseconds")) {
            issues.addAll(cfg.setLatencyRange(latency.getLong("minMilliseconds"), latency.getLong("maxMilliseconds")));
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
                issues.addAll(cfg.setHttpStatusCode(httpStatus.getInteger("code")));
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
                issues.addAll(cfg.setResponseBodyPercentage(body.getInteger("percentage")));
            }
        }
        JsonObject headers = config.getJsonObject("headers");
        if (headers != null) {
            applyConfigHeaders(cfg, headers, issues);
        }
        if (config.containsKey("level")) {
            issues.addAll(cfg.setTargetLevel(config.getInteger("level")));
        }
        return issues;
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
    private static void applyConfigHeaders(MutableAssaultConfig cfg, JsonObject headers, List<String> issues) {
        cfg.getResponseHeaders().keySet().forEach(cfg::removeResponseHeader);
        for (Map.Entry<String, Object> entry : headers) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                issues.add("Response header name must not be blank");
                continue;
            }
            JsonObject rule = headers.getJsonObject(name);
            if (rule == null) {
                issues.add("Response header '" + name + "' must carry an action and value");
                continue;
            }
            ResponseHeaderAction action = parseHeaderAction(rule.getString("action"));
            if (action == null) {
                issues.add("Unknown response header action '" + rule.getString("action") + "' for header '" + name + "'");
                continue;
            }
            String value = rule.getString("value");
            String safeValue = value != null ? value : "";
            if (!MutableAssaultConfig.isValidResponseHeaderValue(safeValue)) {
                issues.add("Response header '" + name
                        + "' value contains characters that cannot be emitted in an HTTP header");
                continue;
            }
            cfg.setResponseHeader(name, action, safeValue);
        }
    }

    /**
     * Returns the live assault counters since the engine started or counters were last reset.
     *
     * @return a JSON object with the {@code total} count, the {@code since} epoch timestamp, and the {@code byType} map
     */
    public JsonObject getCounters() {
        JsonObject byType = new JsonObject();
        engine.getAssaultCounts().forEach(byType::put);
        return new JsonObject()
                .put("total", engine.getTotalAssaultCount())
                .put("since", engine.getCountersSinceEpoch())
                .put("byType", byType);
    }

    /**
     * Resets all assault counters to zero via the Dev UI.
     *
     * @return a JSON object with the {@code ok} flag
     */
    public JsonObject resetCounters() {
        engine.resetCounters();
        LOG.info("Goblin assault counters reset via Dev UI");
        return new JsonObject().put("ok", true);
    }

    public JsonObject clearHistory() {
        engine.clearHistory();
        LOG.info("Goblin assault history cleared via Dev UI");
        return new JsonObject()
                .put("cleared", true);
    }

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
