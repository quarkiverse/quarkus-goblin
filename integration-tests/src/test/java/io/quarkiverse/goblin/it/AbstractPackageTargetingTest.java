package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.restassured.RestAssured;

/**
 * Shared fixture for the package-based targeting integration tests.
 * <p>
 * Each concrete subclass declares a distinct {@code @TestProfile} that sets the static
 * {@code quarkus.goblin.target.include-packages} / {@code quarkus.goblin.target.exclude-packages} values consumed by
 * {@code GoblinChaosFilter.isTargetEligible()}. The HTTP status assault (503) is enabled so that a request which
 * passes the targeting filters is observably short-circuited.
 */
abstract class AbstractPackageTargetingTest {

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (targeting test)");
        engine.clearHistory();
    }

    /**
     * Performs a {@code GET /api/hello} and asserts the response status code.
     *
     * @param expected the expected HTTP status code
     */
    protected void assertHelloStatus(int expected) {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(expected);
    }

    /**
     * Arms the SERVICE layer with the exception assault (HTTP_IN disarmed), performs a {@code GET /api/service/hello} and
     * asserts whether the service bean -- whose package is subject to the same build-time targeting rules -- was
     * assaulted.
     *
     * @param expectAssaulted whether the {@code SampleService} bean is expected to be eligible
     */
    protected void assertServiceLayerAssaulted(boolean expectAssaulted) {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(false);
        cfg.setExceptionEnabled(true);
        cfg.setLayerEnabled(ChaosLayer.HTTP_IN, false);
        cfg.setLayerEnabled(ChaosLayer.SERVICE, true);
        cfg.setTargetLevel(100);

        RestAssured.given()
                .get("/api/service/hello")
                .then()
                .statusCode(expectAssaulted ? 500 : 200);

        long serviceAssaults = engine.getHistory().stream()
                .filter(record -> "io.quarkiverse.goblin.it.SampleService.hello".equals(record.method()))
                .count();
        assertEquals(expectAssaulted ? 1 : 0, serviceAssaults,
                "unexpected service-layer assaults, history: " + engine.getHistory());
    }
}
