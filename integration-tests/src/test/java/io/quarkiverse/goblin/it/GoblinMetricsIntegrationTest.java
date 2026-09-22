package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultEngine.AssaultRecord;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.metrics.GoblinMetricsObserver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * End-to-end coverage of the optional Micrometer integration: assault activity surfaced through the
 * {@code quarkus-goblin-metrics} module is registered against the application's {@link MeterRegistry} with the
 * expected names and tags, reflected on the live Prometheus backend and the {@code goblin.active} gauge follows the
 * engine state.
 * <p>
 * The test application runs with a short Prometheus step ({@code quarkus.micrometer.export.prometheus.step=PT1S}),
 * because Prometheus-backed counters and timers only report the value of the latest settled step. The assertions poll
 * the registry briefly until the increment is visible; precise accumulation behavior is covered on a plain registry in
 * {@code GoblinMetricsObserverTest}.
 */
@QuarkusTest
public class GoblinMetricsIntegrationTest {

    @Inject
    AssaultEngine engine;

    @Inject
    MeterRegistry registry;

    @Inject
    PrometheusMeterRegistry prometheusRegistry;

    @BeforeEach
    void start() {
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
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(100);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @Test
    public void serverLatencyAssaultExposesMetrics() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);

        assertHistory("latency", p -> p.equals("SampleResource.hello"));
        awaitCounter("latency", "server");
        awaitTimerCount("server");
        assertEquals(1, activeGauge(), "engine must be active during the assault");
    }

    @Test
    public void webClientAssaultExposesMetrics() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);

        RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(200);

        assertHistory("latency", p -> p.startsWith("WebClient"));
        awaitCounter("latency", "webclient");
        awaitTimerCount("webclient");
    }

    @Test
    public void restClientAssaultExposesMetrics() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);

        RestAssured.given()
                .get("/api/proxy")
                .then()
                .statusCode(200);

        assertHistory("latency", p -> p.startsWith("REST-Client"));
        awaitCounter("latency", "rest-client");
        awaitTimerCount("rest-client");
    }

    @Test
    public void exceptionAssaultIsCounted() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(true);
        cfg.setExceptionType("java.lang.IllegalStateException");
        cfg.setExceptionMessage("downstream is in trouble");

        RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(500);

        assertHistory("exception", p -> p.startsWith("WebClient"));
        awaitCounter("exception", "webclient");
    }

    @Test
    public void latencyTimerIsExportedAsPrometheusHistogram() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);

        assertHistory("latency", p -> p.equals("SampleResource.hello"));
        awaitTimerCount("server");

        String scrape = awaitScrapeContaining("goblin_latency_injected_seconds_bucket");
        assertTrue(scrape.contains("goblin_latency_injected_seconds_count{source=\"server\""),
                "expected a cumulative count line in the scrape: " + scrape);
        var infBucket = java.util.regex.Pattern
                .compile("goblin_latency_injected_seconds_bucket\\{source=\"server\",le=\"\\+Inf\",} ([0-9.]+)")
                .matcher(scrape);
        assertTrue(infBucket.find() && Double.parseDouble(infBucket.group(1)) >= 1,
                "expected at least one record in the +Inf bucket of the server latency histogram: " + scrape);
    }

    @Test
    public void engineDeactivationIsReflectedInGauge() {
        engine.setActive(false);
        assertEquals(0, activeGauge(), "deactivating the engine must set goblin.active to 0");

        engine.setActive(true);
        assertEquals(1, activeGauge(), "reactivating the engine must set goblin.active to 1");
    }

    private void assertHistory(String type, java.util.function.Predicate<String> method) {
        List<AssaultRecord> history = engine.getHistory();
        assertTrue(history.stream().anyMatch(r -> r.type().equals(type) && method.test(r.method())),
                "expected a '" + type + "' assault in the history, got: " + history);
    }

    private void awaitCounter(String type, String source) {
        assertNotNull(registry.find(GoblinMetricsObserver.TOTAL_METRIC)
                .tag("type", type)
                .tag("source", source)
                .counter(), "no goblin.assaults.total counter registered for " + source + "/" + type);
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            long count = totalCount(type, source);
            if (count >= 1) {
                return;
            }
            sleepQuietly(50);
        }
        assertEquals(1, totalCount(type, source), "goblin.assaults.total for " + source + "/" + type + " must be incremented");
    }

    private void awaitTimerCount(String source) {
        assertNotNull(registry.find(GoblinMetricsObserver.LATENCY_METRIC)
                .tag("source", source)
                .timer(), "no goblin.latency.injected.seconds timer registered for " + source);
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            long count = timerCount(source);
            if (count >= 1) {
                return;
            }
            sleepQuietly(50);
        }
        assertEquals(1, timerCount(source), "goblin.latency.injected.seconds for " + source + " must be recorded");
    }

    private long totalCount(String type, String source) {
        Counter counter = registry.find(GoblinMetricsObserver.TOTAL_METRIC)
                .tag("type", type)
                .tag("source", source)
                .counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private long timerCount(String source) {
        Timer timer = registry.find(GoblinMetricsObserver.LATENCY_METRIC)
                .tag("source", source)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private double activeGauge() {
        assertNotNull(registry.find(GoblinMetricsObserver.ACTIVE_METRIC).gauge(),
                "goblin.active gauge must be registered");
        return registry.get(GoblinMetricsObserver.ACTIVE_METRIC).gauge().value();
    }

    private String awaitScrapeContaining(String fragment) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String scrape = prometheusRegistry.scrape();
            if (scrape.contains(fragment)) {
                return scrape;
            }
            sleepQuietly(50);
        }
        throw new AssertionError("the Prometheus scrape never contained: " + fragment);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}