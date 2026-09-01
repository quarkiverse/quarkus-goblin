package io.quarkiverse.goblin.dev;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class GoblinJsonRPCService {

    private static final Logger LOG = Logger.getLogger(GoblinJsonRPCService.class);

    @Inject
    AssaultEngine engine;

    public JsonObject getStatus() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        return new JsonObject()
                .put("active", engine.isActive())
                .put("latencyEnabled", cfg != null && cfg.isLatencyEnabled())
                .put("exceptionEnabled", cfg != null && cfg.isExceptionEnabled())
                .put("httpStatusEnabled", cfg != null && cfg.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", cfg != null && cfg.isDependencyDegradationEnabled())
                .put("level", cfg != null ? cfg.getTargetLevel() : 100);
    }

    public JsonObject getConfig() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return new JsonObject();
        }

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
                .put("latencyEnabled", cfg.isLatencyEnabled())
                .put("exceptionEnabled", cfg.isExceptionEnabled())
                .put("httpStatusEnabled", cfg.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", cfg.isDependencyDegradationEnabled())
                .put("latency", latency)
                .put("exception", exception)
                .put("httpStatus", httpStatus)
                .put("level", cfg.getTargetLevel());
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

    public JsonObject setLatencyRange(long minMs, long maxMs) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        long prevMin = cfg.getLatencyMinMs();
        long prevMax = cfg.getLatencyMaxMs();
        cfg.setLatencyMinMs(minMs);
        cfg.setLatencyMaxMs(maxMs);
        LOG.warnf("Goblin latency changed: %d-%d ms -> %d-%d ms", prevMin, prevMax, minMs, maxMs);
        return new JsonObject()
                .put("ok", true)
                .put("minMilliseconds", cfg.getLatencyMinMs())
                .put("maxMilliseconds", cfg.getLatencyMaxMs());
    }

    public JsonObject setExceptionConfig(String type, String message) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        String prevType = cfg.getExceptionType();
        cfg.setExceptionType(type);
        cfg.setExceptionMessage(message);
        LOG.warnf("Goblin exception changed: %s -> %s", prevType, type);
        return new JsonObject()
                .put("ok", true)
                .put("type", cfg.getExceptionType())
                .put("message", cfg.getExceptionMessage());
    }

    public JsonObject setHttpStatusConfig(int code, String message) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        int prevCode = cfg.getHttpStatusCode();
        cfg.setHttpStatusCode(code);
        cfg.setHttpStatusMessage(message);
        LOG.warnf("Goblin HTTP status changed: %d -> %d", prevCode, code);
        return new JsonObject()
                .put("ok", true)
                .put("code", cfg.getHttpStatusCode())
                .put("message", cfg.getHttpStatusMessage());
    }

    public JsonObject setTargetLevel(int level) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        int previous = cfg.getTargetLevel();
        cfg.setTargetLevel(level);
        LOG.warnf("Goblin target level changed: %d%% -> %d%%", previous, level);
        return new JsonObject()
                .put("ok", true)
                .put("level", cfg.getTargetLevel());
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
