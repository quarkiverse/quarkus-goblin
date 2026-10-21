package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * End-to-end coverage of client-side assaults applied to outbound MicroProfile REST Client calls.
 */
@QuarkusTest
public class GoblinClientAssaultIntegrationTest {

    @Inject
    AssaultEngine engine;

    @Inject
    @RestClient
    SampleClient client;

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

    @Test
    public void testClientCallWorksWhenNoClientAssaultEnabled() {
        assertEquals("hello from Goblin test app", client.hello());
        assertTrue(engine.getHistory().isEmpty(), "no assault should be recorded");
    }

    @Test
    public void testClientLatencyAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(300);
        cfg.setLatencyMaxMs(300);

        long start = System.currentTimeMillis();
        assertEquals("hello from Goblin test app", client.hello());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 250, "expected at least 250ms client delay, got " + elapsed + "ms");
        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("latency", record.type());
        assertEquals(AssaultType.LATENCY, record.assaultType());
        assertTrue(record.method().contains("/api/hello"),
                "expected the outbound URI in the record, got: " + record.method());
    }

    @Test
    public void testClientLatencyDoesNotAffectIncomingRequests() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(1000);
        cfg.setLatencyMaxMs(1000);

        long start = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 900, "incoming requests must not be delayed by client latency, took " + elapsed + "ms");
        assertTrue(engine.getHistory().isEmpty(), "no server-side assault should be recorded");
    }

    @Test
    public void testClientExceptionAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(true);
        cfg.setExceptionType("java.lang.IllegalStateException");
        cfg.setExceptionMessage("downstream is in trouble");

        RuntimeException thrown = assertThrows(RuntimeException.class, client::hello);
        assertTrue(containsMessage(thrown, "downstream is in trouble"),
                "expected the configured message in the exception chain, got: " + thrown);

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("exception", record.type());
        assertEquals(AssaultType.EXCEPTION, record.assaultType());
        assertTrue(record.method().contains("/api/hello"),
                "expected the outbound URI in the record, got: " + record.method());
    }

    @Test
    public void testClientCombinedLatencyAndException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setClientExceptionEnabled(true);
        cfg.setLatencyMinMs(200);
        cfg.setLatencyMaxMs(200);
        cfg.setExceptionType("java.lang.RuntimeException");

        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class, client::hello);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 150, "expected the delay before the client exception, got " + elapsed + "ms");
        assertEquals(2, engine.getHistory().size());
        assertEquals("latency", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.LATENCY, engine.getHistory().getFirst().assaultType());
        assertEquals("exception", engine.getHistory().get(1).type());
        assertEquals(AssaultType.EXCEPTION, engine.getHistory().get(1).assaultType());
    }

    @Test
    public void testClientAssaultRespectsTargetLevel() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setClientExceptionEnabled(true);
        cfg.setTargetLevel(0);

        assertEquals("hello from Goblin test app", client.hello());
        assertTrue(engine.getHistory().isEmpty(), "level 0 must spare outbound calls");
    }

    private static boolean containsMessage(Throwable throwable, String message) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    public void guardedRemoteCallAnswersWhenNoClientAssaultIsEnabled() {
        RestAssured.given()
                .get("/api/service/remote")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("remote: hello from Goblin test app"));
    }

    @Test
    public void clientExceptionIsAnsweredByTheFallbackOfTheCaller() {
        engine.getMutableConfig().setClientExceptionEnabled(true);

        RestAssured.given()
                .get("/api/service/remote")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("remote fallback reply"));

        assertTrue(engine.getHistory().stream().anyMatch(record -> "exception".equals(record.type())
                && record.method().startsWith("REST-Client GET") && record.method().endsWith("/api/hello")),
                "the outbound call must have been assaulted, got: " + engine.getHistory());
    }

    @Test
    public void clientLatencyAboveTheTimeoutIsAnsweredByTheFallback() {
        // the first call initialises the REST Client and Fault Tolerance, which would count against the timing below
        RestAssured.given().get("/api/service/remote").then().statusCode(200);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(600);
        cfg.setLatencyMaxMs(700);

        long start = System.nanoTime();
        RestAssured.given()
                .get("/api/service/remote")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("remote fallback reply"));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 600,
                "@Timeout(400ms) must give up before the 600-700 ms injected latency elapses, took " + elapsedMs + " ms");
    }
}
