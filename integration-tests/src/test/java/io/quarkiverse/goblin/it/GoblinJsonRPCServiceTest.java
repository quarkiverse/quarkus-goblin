package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.dev.GoblinJsonRPCService;
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
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(200);
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
        assertTrue(status.containsKey("clientLatencyEnabled"));
        assertTrue(status.containsKey("clientExceptionEnabled"));
        assertTrue(status.containsKey("responseBodyEnabled"));
        assertTrue(status.containsKey("responseHeaderEnabled"));
        assertTrue(status.containsKey("level"));
    }

    // ==================== toggleActive ====================

    @Test
    public void testToggleActive() {
        engine.setActive(false);
        JsonObject result = jsonRpc.toggleActive();
        assertTrue(result.getBoolean("active"));
        assertTrue(engine.isActive());
        assertTrue(result.containsKey("profile"), "toggleActive must return the full config");
        assertTrue(result.containsKey("exceptionPresets"), "toggleActive must return the full config");

        result = jsonRpc.toggleActive();
        assertFalse(result.getBoolean("active"));
        assertFalse(engine.isActive());
    }

    @Test
    public void testSetActive() {
        JsonObject result = jsonRpc.setActive(true);
        assertTrue(result.getBoolean("active"));
        assertTrue(result.containsKey("profile"), "setActive must return the full config");

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

    @Test
    public void testSetLatencyRangeSwappedReportsWarning() {
        JsonObject result = jsonRpc.setLatencyRange(1000, 500);
        assertTrue(result.getBoolean("ok"));
        assertEquals(500, result.getInteger("minMilliseconds"));
        assertEquals(1000, result.getInteger("maxMilliseconds"));
        assertTrue(result.getString("warning").contains("Swapping"));
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

    @Test
    public void testSetHttpStatusOutOfRangeDefaultsTo503WithWarning() {
        JsonObject result = jsonRpc.setHttpStatusConfig(999, "Nope");
        assertTrue(result.getBoolean("ok"));
        assertEquals(503, result.getInteger("code"));
        assertTrue(result.getString("warning").contains("100-599"));
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

    @Test
    public void testSetTargetLevelClampedReportsWarning() {
        JsonObject result = jsonRpc.setTargetLevel(150);
        assertEquals(100, result.getInteger("level"));
        assertTrue(result.getString("warning").contains("Clamping"));
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
        assertTrue(config.containsKey("body"));
        assertTrue(config.containsKey("level"));
        assertTrue(config.containsKey("exceptionPresets"), "the full config must expose the server-side exception presets");
        assertFalse(config.getJsonArray("exceptionPresets").isEmpty());

        JsonObject latency = config.getJsonObject("latency");
        assertNotNull(latency);
        assertTrue(latency.containsKey("minMilliseconds"));
        assertTrue(latency.containsKey("maxMilliseconds"));

        JsonObject body = config.getJsonObject("body");
        assertNotNull(body);
        assertTrue(body.containsKey("mode"));
        assertTrue(body.containsKey("percentage"));
    }

    // ==================== response body assault ====================

    @Test
    public void testToggleResponseBody() {
        assertFalse(engine.getMutableConfig().isResponseBodyEnabled());

        JsonObject result = jsonRpc.toggleResponseBody();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseBodyEnabled"));
        assertTrue(engine.getMutableConfig().isResponseBodyEnabled());

        result = jsonRpc.toggleResponseBody();
        assertFalse(result.getBoolean("responseBodyEnabled"));
        assertFalse(engine.getMutableConfig().isResponseBodyEnabled());
    }

    @Test
    public void testSetResponseBodyConfig() {
        JsonObject result = jsonRpc.setResponseBodyConfig("inflate", 150);
        assertTrue(result.getBoolean("ok"));
        assertEquals("INFLATE", result.getString("mode"));
        assertEquals(150, result.getInteger("percentage"));
        assertEquals(io.quarkiverse.goblin.ResponseBodyMode.INFLATE,
                engine.getMutableConfig().getResponseBodyMode());
        assertEquals(150, engine.getMutableConfig().getResponseBodyPercentage());
    }

    @Test
    public void testSetResponseBodyConfigUnknownMode() {
        JsonObject result = jsonRpc.setResponseBodyConfig("SHRINK", 50);
        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    @Test
    public void testSetResponseBodyPercentageClampedWithWarning() {
        JsonObject result = jsonRpc.setResponseBodyConfig("TRUNCATE", 250);
        assertTrue(result.getBoolean("ok"));
        assertEquals(100, result.getInteger("percentage"));
        assertTrue(result.getString("warning").contains("Clamping"));
    }

    @Test
    public void testResponseBodyConfigEndToEnd() {
        jsonRpc.toggleResponseBody();
        jsonRpc.setResponseBodyConfig("TRUNCATE", 50);

        io.restassured.RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("hello from Go"));
    }

    // ==================== response header assault ====================

    @Test
    public void testToggleResponseHeader() {
        assertFalse(engine.getMutableConfig().isResponseHeaderEnabled());
        JsonObject result = jsonRpc.toggleResponseHeader();
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseHeaderEnabled"));
        assertTrue(engine.getMutableConfig().isResponseHeaderEnabled());

        result = jsonRpc.toggleResponseHeader();
        assertFalse(engine.getMutableConfig().isResponseHeaderEnabled());
    }

    @Test
    public void testSetResponseHeaderInfo() {
        JsonObject result = jsonRpc.setResponseHeaderInfo("X-Goblin", "set", "chaos");
        assertTrue(result.getBoolean("ok"));
        assertEquals("SET", result.getString("action"));
        assertEquals("chaos", result.getString("value"));
        io.quarkiverse.goblin.MutableAssaultConfig.HeaderRule rule = engine.getMutableConfig().getResponseHeaders()
                .get("X-Goblin");
        assertNotNull(rule);
        assertEquals(io.quarkiverse.goblin.ResponseHeaderAction.SET, rule.action());
        assertEquals("chaos", rule.value());
    }

    @Test
    public void testSetResponseHeaderInfoInvalidAction() {
        JsonObject result = jsonRpc.setResponseHeaderInfo("X-Goblin", "bogus", "chaos");
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("error").contains("SET"));
        assertTrue(engine.getMutableConfig().getResponseHeaders().isEmpty());
    }

    @Test
    public void testSetResponseHeaderInfoBlankName() {
        JsonObject result = jsonRpc.setResponseHeaderInfo("   ", "SET", "chaos");
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("error").contains("blank"));
    }

    @Test
    public void testSetResponseHeaderInfoRejectsControlCharacters() {
        JsonObject result = jsonRpc.setResponseHeaderInfo("X-Goblin", "SET", "chaos\r\nInjected: true");
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("error").contains("CR, LF"));
        assertTrue(engine.getMutableConfig().getResponseHeaders().isEmpty());
    }

    @Test
    public void testRemoveResponseHeader() {
        jsonRpc.setResponseHeaderInfo("X-Goblin", "SET", "chaos");
        assertFalse(engine.getMutableConfig().getResponseHeaders().isEmpty());

        JsonObject result = jsonRpc.removeResponseHeader("X-Goblin");
        assertTrue(result.getBoolean("ok"));
        assertTrue(engine.getMutableConfig().getResponseHeaders().isEmpty());
    }

    @Test
    public void testHeadersInGetConfig() {
        jsonRpc.setResponseHeaderInfo("X-Goblin", "SET", "v1");
        JsonObject config = jsonRpc.getConfig();
        assertTrue(config.getBoolean("responseHeaderEnabled") == Boolean.FALSE
                || config.containsKey("responseHeaderEnabled"));
        assertTrue(config.containsKey("headers"));
        JsonObject headers = config.getJsonObject("headers");
        assertNotNull(headers.getJsonObject("X-Goblin"));
        assertEquals("SET", headers.getJsonObject("X-Goblin").getString("action"));
    }

    @Test
    public void testResponseHeaderConfigEndToEnd() {
        jsonRpc.toggleResponseHeader();
        jsonRpc.setResponseHeaderInfo("X-Goblin", "SET", "chaos");

        io.restassured.RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("X-Goblin", org.hamcrest.Matchers.equalTo("chaos"));
    }

    // ==================== AssaultType enum ====================

    @Test
    public void testAssaultTypeEnumValues() {
        AssaultType[] types = AssaultType.values();
        assertEquals(6, types.length);
        assertEquals(AssaultType.LATENCY, AssaultType.valueOf("LATENCY"));
        assertEquals(AssaultType.EXCEPTION, AssaultType.valueOf("EXCEPTION"));
        assertEquals(AssaultType.HTTP_STATUS, AssaultType.valueOf("HTTP_STATUS"));
        assertEquals(AssaultType.DEPENDENCY_DEGRADATION, AssaultType.valueOf("DEPENDENCY_DEGRADATION"));
        assertEquals(AssaultType.RESPONSE_BODY, AssaultType.valueOf("RESPONSE_BODY"));
        assertEquals(AssaultType.RESPONSE_HEADER, AssaultType.valueOf("RESPONSE_HEADER"));
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

    // ==================== kill switch, reset, import/export, counters ====================

    @Test
    public void testDisableAllDeactivatesAndTurnsEverythingOff() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setHttpStatusEnabled(true);
        engine.setActive(true);

        JsonObject result = jsonRpc.disableAll();

        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("active"));
        assertFalse(engine.isActive());
        assertFalse(result.getBoolean("latencyEnabled"));
        assertFalse(result.getBoolean("httpStatusEnabled"));
        assertEquals("NONE", result.getString("profile"));
    }

    @Test
    public void testApplyConfigAppliesProvidedFieldsOnly() {
        JsonObject result = jsonRpc.applyConfig(new JsonObject()
                .put("profile", "SLOW_FAILURE")
                .put("level", 30).getMap());

        assertTrue(result.getBoolean("ok"));
        assertEquals("SLOW_FAILURE", result.getString("profile"));
        assertEquals(30, result.getInteger("level"));
        assertTrue(result.getBoolean("exceptionEnabled"), "SLOW_FAILURE enables exception");
    }

    @Test
    public void testGetCountersAndReset() {
        engine.resetCounters();
        engine.recordAssault("SampleResource.hello", "latency", 12);
        engine.recordAssault("SampleResource.hello", "response-body-truncate");

        JsonObject counters = jsonRpc.getCounters();
        assertEquals(2, counters.getInteger("total"));
        assertEquals(1L, counters.getJsonObject("byType").getLong("latency"));
        assertTrue(counters.containsKey("since"));

        JsonObject reset = jsonRpc.resetCounters();
        assertTrue(reset.getBoolean("ok"));
        assertEquals(0, jsonRpc.getCounters().getInteger("total"));
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
