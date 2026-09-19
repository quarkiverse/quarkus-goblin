package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.GoblinConfig;
import io.quarkiverse.goblin.MutableAssaultConfig;
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
        cfg.setClientLatencyEnabled(false);
        cfg.setClientExceptionEnabled(false);
        cfg.setResponseBodyEnabled(false);
        cfg.setResponseHeaderEnabled(false);
        cfg.getResponseHeaders().keySet().forEach(cfg::removeResponseHeader);
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

    // ==================== Response body assault ====================

    @Test
    public void testResponseBodyTruncateToFiftyPercent() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(io.quarkiverse.goblin.ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("Content-Length", "13")
                .body(equalTo("hello from Go"));
    }

    @Test
    public void testResponseBodyInflateAdvertisesShorterLengthThanEmitted() throws Exception {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(io.quarkiverse.goblin.ResponseBodyMode.INFLATE);
        cfg.setResponseBodyPercentage(200);

        RawResponse response = rawGet("/api/hello");

        assertEquals("26", response.headers().get("content-length"),
                "INFLATE must advertise the original, smaller length");
        assertEquals(52, response.body().length,
                "the emitted payload must be larger than the advertised Content-Length");
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.startsWith("hello from Goblin test app"));
        assertTrue(body.contains("[goblin-response-inflated]"));
    }

    @Test
    public void testResponseBodyOffLeavesBodyUntouched() {
        engine.setActive(true);

        String body = RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .extract().asString();

        assertEquals("hello from Goblin test app", body);
    }

    // ==================== Response headers ====================

    @Test
    public void testResponseHeaderSetInjectsHeader() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", io.quarkiverse.goblin.ResponseHeaderAction.SET, "chaos");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("X-Goblin", equalTo("chaos"));
    }

    @Test
    public void testResponseHeaderSetReplacesExistingHeader() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("Content-Type", io.quarkiverse.goblin.ResponseHeaderAction.SET, "application/x-goblin");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("Content-Type", equalTo("application/x-goblin"));
    }

    @Test
    public void testResponseHeaderRemoveDeletesHeader() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("Content-Type", io.quarkiverse.goblin.ResponseHeaderAction.REMOVE, "");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("Content-Type", nullValue());
    }

    @Test
    public void testResponseHeaderDisabledLeavesHeadersUntouched() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeader("X-Goblin", io.quarkiverse.goblin.ResponseHeaderAction.SET, "chaos");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("X-Goblin", nullValue());
    }

    @Test
    public void testResponseHeaderAssaultRecordedInHistory() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", io.quarkiverse.goblin.ResponseHeaderAction.SET, "chaos");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("X-Goblin", notNullValue());

        boolean found = engine.getHistory().stream()
                .anyMatch(record -> "response-header-set:X-Goblin".equals(record.type()));
        assertTrue(found, "history must contain a response-header-set:X-Goblin record");
    }

    /**
     * Sends a raw HTTP/1.1 request with {@code Connection: close} and reads every emitted byte until the server closes
     * the connection. This reads the payload independently of its advertised {@code Content-Length}, which is required
     * to observe the deliberate header/payload mismatch produced by {@link io.quarkiverse.goblin.ResponseBodyMode#INFLATE}.
     *
     * @param path the request path
     * @return the parsed response headers (lower-cased names) and the raw body bytes
     */
    private RawResponse rawGet(String path) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", 8081)) {
            socket.setSoTimeout(2000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] raw = socket.getInputStream().readAllBytes();
            String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
            int separator = text.indexOf("\r\n\r\n");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            for (String line : text.substring(0, separator).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            return new RawResponse(headers, java.util.Arrays.copyOfRange(raw, separator + 4, raw.length));
        }
    }

    private record RawResponse(java.util.Map<String, String> headers, byte[] body) {
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
