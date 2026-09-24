package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

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
 * Exercises the DATABASE layer (issue #54, phase 2): faults injected by the Agroal pool interceptor at JDBC connection
 * acquisition, below the repository, and observed by the Fault Tolerance annotations of the service above it.
 */
@QuarkusTest
public class GoblinDatabaseLayerIntegrationTest {

    private static final String DEFAULT_DATASOURCE = "Database <default> connection";

    @Inject
    AssaultEngine engine;

    @Inject
    SampleService service;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyRange(100, 150);
        cfg.setLayers(List.of(ChaosLayer.DATABASE));
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    public void databaseLayerIsAvailableWithADatasource() {
        assertTrue(engine.isLayerAvailable(ChaosLayer.DATABASE), "the application has a JDBC datasource");
    }

    @Test
    public void databaseExceptionFailsTheConnectionAcquisition() {
        engine.getMutableConfig().setExceptionEnabled(true);

        RestAssured.given()
                .get("/api/db/ping")
                .then()
                .statusCode(500);

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertEquals(1, records.stream()
                .filter(record -> DEFAULT_DATASOURCE.equals(record.method()) && "exception".equals(record.type()))
                .count(), "the connection acquisition must be exception-assaulted once, got: " + records);
        assertTrue(records.stream().noneMatch(record -> record.method().startsWith("io.quarkiverse.goblin.it.")),
                "the DATABASE layer shadows the SERVICE layer for this request, got: " + records);
    }

    @Test
    public void databaseLatencyDelaysTheQueryWithoutFailingIt() {
        engine.getMutableConfig().setLatencyEnabled(true);

        RestAssured.given()
                .get("/api/db/ping")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("db: 1"));

        AssaultEngine.AssaultRecord latency = engine.getHistory().stream()
                .filter(record -> DEFAULT_DATASOURCE.equals(record.method()) && "latency".equals(record.type()))
                .findFirst()
                .orElseThrow();
        assertTrue(latency.latencyMs() >= 100 && latency.latencyMs() <= 150,
                "the recorded latency must match the armed range, got: " + latency.latencyMs());
    }

    @Test
    public void retryThenFallbackAnswerDatabaseFaults() {
        engine.getMutableConfig().setExceptionEnabled(true);

        RestAssured.given()
                .get("/api/db/retry")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.equalTo("database fallback reply"));

        long attempts = engine.getHistory().stream()
                .filter(record -> DEFAULT_DATASOURCE.equals(record.method()) && "exception".equals(record.type()))
                .count();
        assertEquals(3, attempts,
                "@Retry(maxRetries=2) on the service must acquire a connection three times, each one assaulted at "
                        + "level 100, before the @Fallback answers, got " + attempts);
    }

    @Test
    public void databaseLayerInertWhenNotArmed() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setLayers(List.of(ChaosLayer.SERVICE));

        RestAssured.given()
                .get("/api/db/ping")
                .then()
                .statusCode(500);

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertTrue(records.stream().noneMatch(record -> record.method().startsWith("Database ")),
                "no database assault when the DATABASE layer is not armed, got: " + records);
        assertTrue(records.stream()
                .anyMatch(record -> "io.quarkiverse.goblin.it.SampleService.databasePing".equals(record.method())),
                "the armed SERVICE layer assaults the service bean instead, got: " + records);
        assertTrue(records.stream().noneMatch(record -> record.method().contains("SampleRepository")),
                "the repository is a nested call of the service and is not assaulted a second time, got: " + records);
    }

    @Test
    public void noDatabaseAssaultOutsideAnArmedRequest() throws Exception {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);

        // a direct call from the test thread has no inbound request: no layer was resolved
        assertEquals("db: 1", service.databasePing());
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }
}
