package io.quarkiverse.goblin.dev;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultProfile;
import io.quarkiverse.goblin.MarkdownReportGenerator;
import io.quarkiverse.goblin.MutableAssaultConfig;
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

        return new JsonObject()
                .put("profile", cfg.getProfile().name())
                .put("latencyEnabled", cfg.isLatencyEnabled())
                .put("exceptionEnabled", cfg.isExceptionEnabled())
                .put("httpStatusEnabled", cfg.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", cfg.isDependencyDegradationEnabled())
                .put("clientLatencyEnabled", cfg.isClientLatencyEnabled())
                .put("clientExceptionEnabled", cfg.isClientExceptionEnabled())
                .put("latency", latency)
                .put("exception", exception)
                .put("httpStatus", httpStatus)
                .put("level", cfg.getTargetLevel());
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
        return new JsonObject()
                .put("active", engine.isActive());
    }

    public JsonObject setActive(boolean active) {
        engine.setActive(active);
        LOG.warnf("Goblin chaos %s via Dev UI", active ? "ACTIVATED" : "DEACTIVATED");
        return new JsonObject()
                .put("active", engine.isActive());
    }

    public JsonObject toggleLatency() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(!cfg.isLatencyEnabled());
        LOG.warnf("Goblin latency %s via Dev UI", cfg.isLatencyEnabled() ? "ENABLED" : "DISABLED");
        return new JsonObject()
                .put("ok", true)
                .put("latencyEnabled", cfg.isLatencyEnabled());
    }

    public JsonObject toggleException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(!cfg.isExceptionEnabled());
        LOG.warnf("Goblin exception %s via Dev UI", cfg.isExceptionEnabled() ? "ENABLED" : "DISABLED");
        return new JsonObject()
                .put("ok", true)
                .put("exceptionEnabled", cfg.isExceptionEnabled());
    }

    public JsonObject toggleHttpStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(!cfg.isHttpStatusEnabled());
        LOG.warnf("Goblin HTTP status %s via Dev UI", cfg.isHttpStatusEnabled() ? "ENABLED" : "DISABLED");
        return new JsonObject()
                .put("ok", true)
                .put("httpStatusEnabled", cfg.isHttpStatusEnabled());
    }

    public JsonObject toggleDependencyDegradation() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setDependencyDegradationEnabled(!cfg.isDependencyDegradationEnabled());
        LOG.warnf("Goblin dependency degradation %s via Dev UI", cfg.isDependencyDegradationEnabled() ? "ENABLED" : "DISABLED");
        return new JsonObject()
                .put("ok", true)
                .put("dependencyDegradationEnabled", cfg.isDependencyDegradationEnabled());
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
        return new JsonObject()
                .put("ok", true)
                .put("clientLatencyEnabled", cfg.isClientLatencyEnabled());
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
        return new JsonObject()
                .put("ok", true)
                .put("clientExceptionEnabled", cfg.isClientExceptionEnabled());
    }

    public JsonObject setLatencyRange(long minMs, long maxMs) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        long prevMin = cfg.getLatencyMinMs();
        long prevMax = cfg.getLatencyMaxMs();
        List<String> issues = cfg.setLatencyRange(minMs, maxMs);
        LOG.warnf("Goblin latency changed: %d-%d ms -> %d-%d ms", prevMin, prevMax,
                cfg.getLatencyMinMs(), cfg.getLatencyMaxMs());
        return new JsonObject()
                .put("ok", true)
                .put("minMilliseconds", cfg.getLatencyMinMs())
                .put("maxMilliseconds", cfg.getLatencyMaxMs())
                .put("warning", toWarning(issues));
    }

    public JsonObject setExceptionConfig(String type, String message) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        String prevType = cfg.getExceptionType();
        List<String> issues = cfg.setExceptionType(type);
        cfg.setExceptionMessage(message);
        LOG.warnf("Goblin exception changed: %s -> %s", prevType, cfg.getExceptionType());
        return new JsonObject()
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
        return new JsonObject()
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
        return new JsonObject()
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
