package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.runtime.AssaultEngine;
import io.quarkiverse.goblin.runtime.AssaultType;
import io.quarkiverse.goblin.runtime.GoblinJsonRPCService;
import io.quarkiverse.goblin.runtime.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class GoblinJsonRPCServiceTest {

    @Inject
    GoblinJsonRPCService jsonRpc;

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(false);
        cfg.setHttpStatusEnabled(false);
        cfg.setDependencyDegradationEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(200);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    // ==================== getStatus ====================

    @Test
    public void testGetStatus() {
        JsonObject status = jsonRpc.getStatus();
        assertNotNull(status);
        assertTrue(status.containsKey("active"));
        assertTrue(status.containsKey("latencyEnabled"));
        assertTrue(status.containsKey("exceptionEnabled"));
        assertTrue(status.containsKey("httpStatusEnabled"));
        assertTrue(status.containsKey("dependencyDegradationEnabled"));
        assertTrue(status.containsKey("level"));
    }

    // ==================== toggleActive ====================

    @Test
    public void testToggleActive() {
        engine.setActive(false);
        JsonObject result = jsonRpc.toggleActive();
        assertTrue(result.getBoolean("active"));
        assertTrue(engine.isActive());

        result = jsonRpc.toggleActive();
        assertFalse(result.getBoolean("active"));
        assertFalse(engine.isActive());
    }

    @Test
    public void testSetActive() {
        JsonObject result = jsonRpc.setActive(true);
        assertTrue(result.getBoolean("active"));

        result = jsonRpc.setActive(false);
        assertFalse(result.getBoolean("active"));
    }

    // ==================== toggleAssaultTypes ====================

    @Test
    public void testToggleLatency() {
        assertFalse(engine.getMutableConfig().isLatencyEnabled());

        JsonObject result = jsonRpc.toggleLatency();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("latencyEnabled"));
        assertTrue(engine.getMutableConfig().isLatencyEnabled());

        result = jsonRpc.toggleLatency();
        assertFalse(result.getBoolean("latencyEnabled"));
        assertFalse(engine.getMutableConfig().isLatencyEnabled());
    }

    @Test
    public void testToggleException() {
        assertFalse(engine.getMutableConfig().isExceptionEnabled());

        JsonObject result = jsonRpc.toggleException();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("exceptionEnabled"));

        result = jsonRpc.toggleException();
        assertFalse(result.getBoolean("exceptionEnabled"));
    }

    @Test
    public void testToggleHttpStatus() {
        assertFalse(engine.getMutableConfig().isHttpStatusEnabled());

        JsonObject result = jsonRpc.toggleHttpStatus();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("httpStatusEnabled"));

        result = jsonRpc.toggleHttpStatus();
        assertFalse(result.getBoolean("httpStatusEnabled"));
    }

    @Test
    public void testToggleDependencyDegradation() {
        assertFalse(engine.getMutableConfig().isDependencyDegradationEnabled());

        JsonObject result = jsonRpc.toggleDependencyDegradation();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("dependencyDegradationEnabled"));

        result = jsonRpc.toggleDependencyDegradation();
        assertFalse(result.getBoolean("dependencyDegradationEnabled"));
    }

    // ==================== setLatencyRange ====================

    @Test
    public void testSetLatencyRange() {
        JsonObject result = jsonRpc.setLatencyRange(500, 1000);
        assertTrue(result.getBoolean("ok"));
        assertEquals(500, result.getInteger("minMilliseconds"));
        assertEquals(1000, result.getInteger("maxMilliseconds"));
        assertEquals(500, engine.getMutableConfig().getLatencyMinMs());
        assertEquals(1000, engine.getMutableConfig().getLatencyMaxMs());
    }

    // ==================== setExceptionConfig ====================

    @Test
    public void testSetExceptionConfig() {
        JsonObject result = jsonRpc.setExceptionConfig("java.io.IOException", "connection refused");
        assertTrue(result.getBoolean("ok"));
        assertEquals("java.io.IOException", result.getString("type"));
        assertEquals("connection refused", result.getString("message"));
        assertEquals("java.io.IOException", engine.getMutableConfig().getExceptionType());
    }

    // ==================== setHttpStatusConfig ====================

    @Test
    public void testSetHttpStatusConfig() {
        JsonObject result = jsonRpc.setHttpStatusConfig(429, "Too Many Requests");
        assertTrue(result.getBoolean("ok"));
        assertEquals(429, result.getInteger("code"));
        assertEquals("Too Many Requests", result.getString("message"));
        assertEquals(429, engine.getMutableConfig().getHttpStatusCode());
    }

    // ==================== setTargetLevel ====================

    @Test
    public void testSetTargetLevel() {
        JsonObject result = jsonRpc.setTargetLevel(50);
        assertTrue(result.getBoolean("ok"));
        assertEquals(50, result.getInteger("level"));
        assertEquals(50, engine.getMutableConfig().getTargetLevel());
    }

    @Test
    public void testSetTargetLevelClamped() {
        jsonRpc.setTargetLevel(150);
        assertEquals(100, engine.getMutableConfig().getTargetLevel());

        jsonRpc.setTargetLevel(-10);
        assertEquals(0, engine.getMutableConfig().getTargetLevel());
    }

    // ==================== getConfig ====================

    @Test
    public void testGetConfig() {
        JsonObject config = jsonRpc.getConfig();
        assertNotNull(config);
        assertTrue(config.containsKey("latencyEnabled"));
        assertTrue(config.containsKey("exceptionEnabled"));
        assertTrue(config.containsKey("httpStatusEnabled"));
        assertTrue(config.containsKey("dependencyDegradationEnabled"));
        assertTrue(config.containsKey("latency"));
        assertTrue(config.containsKey("exception"));
        assertTrue(config.containsKey("httpStatus"));
        assertTrue(config.containsKey("level"));

        JsonObject latency = config.getJsonObject("latency");
        assertNotNull(latency);
        assertTrue(latency.containsKey("minMilliseconds"));
        assertTrue(latency.containsKey("maxMilliseconds"));
    }

    // ==================== AssaultType enum ====================

    @Test
    public void testAssaultTypeEnumValues() {
        AssaultType[] types = AssaultType.values();
        assertEquals(4, types.length);
        assertEquals(AssaultType.LATENCY, AssaultType.valueOf("LATENCY"));
        assertEquals(AssaultType.EXCEPTION, AssaultType.valueOf("EXCEPTION"));
        assertEquals(AssaultType.HTTP_STATUS, AssaultType.valueOf("HTTP_STATUS"));
        assertEquals(AssaultType.DEPENDENCY_DEGRADATION, AssaultType.valueOf("DEPENDENCY_DEGRADATION"));
    }

    // ==================== history ====================

    @Test
    public void testGetHistoryEmpty() {
        JsonArray history = jsonRpc.getHistory();
        assertNotNull(history);
        assertEquals(0, history.size());
    }

    @Test
    public void testClearHistory() {
        engine.recordAssault("test", "latency");
        assertFalse(engine.getHistory().isEmpty());

        JsonObject result = jsonRpc.clearHistory();
        assertTrue(result.getBoolean("cleared"));
        assertTrue(engine.getHistory().isEmpty());
    }

    // ==================== markdown report ====================

    @Test
    public void testGetMarkdownReport() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(500);

        engine.recordAssault("SampleResource.hello", "latency", 423);
        cfg.setLatencyMinMs(1000);
        cfg.setLatencyMaxMs(5000);
        engine.recordAssault("SampleResource.hello", "latency", 3922);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        engine.recordAssault("SampleResource.slow", "http-status");

        JsonObject result = jsonRpc.getMarkdownReport();
        assertNotNull(result);
        assertTrue(result.containsKey("generatedAt"));
        assertTrue(result.containsKey("markdown"));

        String markdown = result.getString("markdown");
        assertNotNull(markdown);
        assertTrue(markdown.startsWith("# Goblin Chaos Report"));
        assertTrue(markdown.contains("## Current Configuration"));
        assertTrue(markdown.contains("## Assault History"));
        assertTrue(markdown.contains("SampleResource.hello"));
        assertTrue(markdown.contains("SampleResource.slow"));
        assertTrue(markdown.contains("latency"));
        assertTrue(markdown.contains("http-status"));
        assertTrue(markdown.contains("423 ms"));
        assertTrue(markdown.contains("3922 ms"));
        assertTrue(markdown.contains("Active Config at Time of Assault"));
        assertTrue(markdown.contains("latency enabled (100 - 500 ms)"));
        assertTrue(markdown.contains("latency enabled (1000 - 5000 ms)"));
        assertTrue(markdown.contains("httpStatus enabled"));
    }

    @Test
    public void testRecordAssaultCapturesConfigSnapshot() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setExceptionEnabled(true);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(500);

        engine.recordAssault("SampleResource.hello", "latency", 250);
        AssaultEngine.AssaultRecord record = engine.getHistory().get(0);

        assertEquals(250, record.latencyMs());
        assertEquals("latency", record.type());
        assertEquals("SampleResource.hello", record.method());
        assertTrue(record.configSnapshot().contains("latency enabled (100 - 500 ms)"));
        assertTrue(record.configSnapshot().contains("exception enabled"));

        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(false);
        engine.recordAssault("SampleResource.hello", "http-status");
        AssaultEngine.AssaultRecord second = engine.getHistory().get(1);
        assertEquals("no assault enabled", second.configSnapshot());
    }
}
