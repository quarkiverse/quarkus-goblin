package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * Exercises the service-layer chaos assaults (issue #54) against the single {@link SampleService} bean: latency and
 * exception injected by {@link io.quarkiverse.goblin.service.GoblinServiceInterceptor} at the business layer, and the
 * proof that the interceptor runs <em>inside</em> MicroProfile Fault Tolerance so {@code @Retry}, {@code @Fallback} and
 * {@code @Timeout} all observe Goblin faults.
 */
@QuarkusTest
public class GoblinServiceLayerIntegrationTest {

    private static final String SAMPLE_SERVICE_HELLO = "io.quarkiverse.goblin.it.SampleService.hello";
    private static final String SAMPLE_SERVICE_FLAKY = "io.quarkiverse.goblin.it.SampleService.flaky";
    private static final String SAMPLE_SERVICE_FALLBACKABLE = "io.quarkiverse.goblin.it.SampleService.fallbackable";
    private static final String SAMPLE_SERVICE_RETRY_FALLBACK = "io.quarkiverse.goblin.it.SampleService.retryThenFallback";
    private static final String SAMPLE_SERVICE_TIMED = "io.quarkiverse.goblin.it.SampleService.timed";
    private static final String SAMPLE_SERVICE_GUARDED = "io.quarkiverse.goblin.it.SampleService.guarded";
    private static final String SAMPLE_SERVICE_NESTED = "io.quarkiverse.goblin.it.SampleService.nested";
    private static final String SAMPLE_DELEGATE = "io.quarkiverse.goblin.it.SampleDelegate";

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(150);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        if (engine.getMutableConfig() != null) {
            engine.getMutableConfig().resetToDefaults();
        }
    }

    @Test
    public void serviceLayerInertWhenNotArmed() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        // an assault is live (latency) but only the default HTTP_IN / HTTP_OUT layers are armed
        cfg.setLatencyEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/hello")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("hello from Goblin SampleService"));

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(records.stream().noneMatch(record -> SAMPLE_SERVICE_HELLO.equals(record.method())),
                "no service-layer assault must fire when the SERVICE layer is not armed, got: " + records);
        assertTrue(records.stream().anyMatch(record -> "SampleResource.serviceHello".equals(record.method())
                && "latency".equals(record.type())),
                "the armed HTTP_IN layer must have assaulted the resource instead, got: " + records);
    }

    @Test
    public void serviceLatencyIsAppliedOnTheBeanNotTheResource() throws Exception {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setLatencyEnabled(true);
        cfg.setTargetLevel(100);

        long start = System.nanoTime();
        String body = RestAssured.given()
                .get("/api/service/hello")
                .then()
                .statusCode(200)
                .extract().body().asString();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("hello from Goblin SampleService", body);
        assertTrue(elapsedMs >= 100, "the bean call must be delayed by 100-150 ms, took " + elapsedMs + " ms");

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(
                records.stream()
                        .anyMatch(record -> SAMPLE_SERVICE_HELLO.equals(record.method()) && "latency".equals(record.type())),
                "a latency assault must be recorded on the service bean, got: " + records);
        AssaultEngine.AssaultRecord latencyRecord = records.stream()
                .filter(record -> SAMPLE_SERVICE_HELLO.equals(record.method()) && "latency".equals(record.type()))
                .findFirst()
                .orElseThrow();
        assertTrue(latencyRecord.latencyMs() >= 100 && latencyRecord.latencyMs() <= 150,
                "the recorded service latency must match the armed 100-150 ms range, got: " + latencyRecord.latencyMs());
        assertTrue(records.stream().noneMatch(record -> record.method().contains("SampleResource")),
                "the JAX-RS resource must never be service-assaulted, got: " + records);
    }

    @Test
    public void serviceExceptionFailsTheRequestAtTheBeanBoundary() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/hello")
                .then()
                .statusCode(500);

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(
                records.stream()
                        .anyMatch(record -> SAMPLE_SERVICE_HELLO.equals(record.method()) && "exception".equals(record.type())),
                "an exception assault must be recorded on the service bean, got: " + records);
    }

    @Test
    public void retryObservesAndRetriesServiceLayerFaults() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/flaky")
                .then()
                .statusCode(500);

        long attempts = engine.getHistory().stream()
                .filter(record -> SAMPLE_SERVICE_FLAKY.equals(record.method()) && "exception".equals(record.type()))
                .count();
        assertEquals(3, attempts,
                "Goblin must run inside @Retry(maxRetries=2): three exceptions before the request fails, got " + attempts);
    }

    @Test
    public void fallbackServesWhenTheServiceLayerThrows() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/fallback")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("fallback reply"));

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(
                records.stream()
                        .anyMatch(record -> SAMPLE_SERVICE_FALLBACKABLE.equals(record.method())
                                && "exception".equals(record.type())),
                "the primary method must have been exception-assaulted and answered by the fallback, got: " + records);
    }

    @Test
    public void retryThenFallbackRetriesOnceThenServesTheFallback() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/retry-fallback")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("fallback reply"));

        long attempts = engine.getHistory().stream()
                .filter(record -> SAMPLE_SERVICE_RETRY_FALLBACK.equals(record.method()) && "exception".equals(record.type()))
                .count();
        assertEquals(2, attempts,
                "@Retry(maxRetries=1) must re-enter the method once before the fallback, got " + attempts);
    }

    @Test
    public void timeoutAbortsALatencyAboveItsThreshold() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(600);
        cfg.setLatencyMaxMs(700);
        cfg.setTargetLevel(100);

        long start = System.nanoTime();
        RestAssured.given()
                .get("/api/service/timeout")
                .then()
                .statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(500));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 600,
                "@Timeout(400ms) must abort the call before the 600-700 ms injected latency elapses, took " + elapsedMs
                        + " ms");

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(
                records.stream()
                        .anyMatch(record -> SAMPLE_SERVICE_TIMED.equals(record.method()) && "latency".equals(record.type())),
                "the injected latency (600-700 ms) must have been recorded before the @Timeout(400ms) aborted the call, got: "
                        + records);
    }

    @Test
    public void nestedBeanCallsAreAssaultedOnlyOnce() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setLatencyEnabled(true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/nested")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("nested: inner reply"));

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertEquals(1, records.stream().filter(record -> SAMPLE_SERVICE_NESTED.equals(record.method())).count(),
                "the outermost bean call must be assaulted exactly once, got: " + records);
        assertTrue(records.stream().noneMatch(record -> record.method().startsWith(SAMPLE_DELEGATE)),
                "the nested bean call must not be assaulted a second time, got: " + records);
    }

    @Test
    public void circuitBreakerOpensAfterServiceLayerFaults() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);

        for (int i = 0; i < 3; i++) {
            RestAssured.given()
                    .get("/api/service/guarded")
                    .then()
                    .statusCode(500);
        }

        long assaults = engine.getHistory().stream()
                .filter(record -> SAMPLE_SERVICE_GUARDED.equals(record.method()) && "exception".equals(record.type()))
                .count();
        assertEquals(2, assaults,
                "@CircuitBreaker(requestVolumeThreshold=2) must open after two Goblin faults: the third call is rejected "
                        + "by the breaker before reaching the bean, got " + assaults);
    }
}
