package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.dev.AssaultEngine;
import io.quarkiverse.goblin.dev.GoblinConfig;
import io.quarkiverse.goblin.dev.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class GoblinIntegrationTest {

    @Inject
    AssaultEngine engine;

    @Inject
    GoblinConfig config;

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

    // ==================== Endpoint basics ====================

    @Test
    public void testHelloEndpointWorks() {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello from Goblin test app"));
    }

    @Test
    public void testSlowEndpointWorks() {
        RestAssured.given()
                .get("/api/slow")
                .then()
                .statusCode(200)
                .body(equalTo("this endpoint has built-in delay"));
    }

    // ==================== Config ====================

    @Test
    public void testConfigIsLoaded() {
        assertNotNull(config);
        assertTrue(config.enabled());
    }

    @Test
    public void testAssaultConfigDefaults() {
        assertNotNull(config.assault());
        assertNotNull(config.assault().latency());
        assertTrue(config.assault().latency().minMilliseconds() >= 0);
        assertTrue(config.assault().latency().maxMilliseconds() > 0);
    }

    @Test
    public void testTargetConfigDefaults() {
        assertNotNull(config.target());
        assertTrue(config.target().level() >= 0 && config.target().level() <= 100);
    }

    // ==================== Engine activation ====================

    @Test
    public void testEngineActivation() {
        engine.setActive(true);
        engine.getMutableConfig().setLatencyEnabled(true);
        assertTrue(engine.isActive());
        assertTrue(engine.shouldAssault());
    }

    @Test
    public void testEngineDeactivation() {
        engine.setActive(false);
        assertFalse(engine.isActive());
        assertFalse(engine.shouldAssault());
    }

    @Test
    public void testShouldNotAssaultWhenLevelZero() {
        engine.setActive(true);
        engine.getMutableConfig().setTargetLevel(0);
        assertFalse(engine.shouldAssault());
    }

    @Test
    public void testShouldNotAssaultWhenNoTypeEnabled() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(false);
        cfg.setHttpStatusEnabled(false);
        cfg.setDependencyDegradationEnabled(false);
        assertFalse(engine.shouldAssault());
    }

    // ==================== Latency assault ====================

    @Test
    public void testLatencyAssault() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(500);
        cfg.setLatencyMaxMs(600);

        long start = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 450, "Expected at least 450ms delay, got " + elapsed + "ms");
    }

    // ==================== Exception assault ====================

    @Test
    public void testExceptionAssault() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setExceptionType("java.lang.RuntimeException");
        cfg.setExceptionMessage("test exception");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(500);
    }

    // ==================== HTTP Status assault ====================

    @Test
    public void testHttpStatusAssault() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (test)");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503)
                .body(equalTo("Service Unavailable (test)"));
    }

    @Test
    public void testHttpStatusAssaultCustomCode() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(429);
        cfg.setHttpStatusMessage("Too Many Requests");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(429)
                .body(equalTo("Too Many Requests"));
    }

    // ==================== Dependency degradation assault ====================

    @Test
    public void testDependencyDegradationAssault() {
        engine.setActive(true);
        engine.getMutableConfig().setDependencyDegradationEnabled(true);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503)
                .body(equalTo("Dependency unavailable (Goblin chaos)"));
    }

    // ==================== Multiple assault types ====================

    @Test
    public void testLatencyThenException() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(200);
        cfg.setLatencyMaxMs(300);
        cfg.setExceptionEnabled(true);
        cfg.setExceptionType("java.lang.RuntimeException");
        cfg.setExceptionMessage("slow failure");

        long start = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(500);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 150, "Expected delay before exception, got " + elapsed + "ms");
    }

    @Test
    public void testLatencyThenHttpStatus() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(200);
        cfg.setLatencyMaxMs(300);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);

        long start = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 150, "Expected delay before HTTP status, got " + elapsed + "ms");
    }

    // ==================== Targeting ====================

    @Test
    public void testLevelZeroBlocksAllAssaults() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setTargetLevel(0);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello from Goblin test app"));
    }

    // ==================== History ====================

    @Test
    public void testAssaultHistoryRecording() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503);

        assertFalse(engine.getHistory().isEmpty());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("SampleResource.hello", record.method());
        assertEquals("http-status", record.type());
    }

    @Test
    public void testHistoryClear() {
        engine.setActive(true);
        engine.recordAssault("test", "latency");
        assertFalse(engine.getHistory().isEmpty());

        engine.clearHistory();
        assertTrue(engine.getHistory().isEmpty());
    }
}
