package io.quarkiverse.goblin.it;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;

import io.quarkiverse.goblin.AssaultEngine;
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
        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(false);
        cfg.setHttpStatusEnabled(true);
        cfg.setDependencyDegradationEnabled(false);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (targeting test)");
        cfg.setTargetLevel(100);
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
}