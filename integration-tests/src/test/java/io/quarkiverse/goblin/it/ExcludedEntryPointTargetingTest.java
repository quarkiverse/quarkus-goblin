package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * A request whose entry point sits in an excluded package is out of the blast radius on every layer: the targeted
 * service and the datasource it calls are not assaulted on its behalf, while the same service called from a targeted
 * endpoint still is.
 */
@QuarkusTest
@TestProfile(ExcludedEntryPointTargetingTest.ExcludedEntryPointProfile.class)
class ExcludedEntryPointTargetingTest {

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(true);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    void databaseLayerSparesAnExcludedEntryPoint() {
        engine.getMutableConfig().setLayers(List.of(ChaosLayer.DATABASE));

        RestAssured.given().get("/api/excluded/db").then().statusCode(200).body(equalTo("db: 1"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());

        RestAssured.given().get("/api/db/ping").then().statusCode(500);
        assertTrue(engine.getHistory().stream().anyMatch(record -> record.method().startsWith("Database ")),
                "a targeted entry point is still assaulted, got: " + engine.getHistory());
    }

    @Test
    void serviceLayerSparesAnExcludedEntryPoint() {
        engine.getMutableConfig().setLayers(List.of(ChaosLayer.SERVICE));

        RestAssured.given().get("/api/excluded/service").then().statusCode(200)
                .body(equalTo("hello from Goblin SampleService"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());

        RestAssured.given().get("/api/service/hello").then().statusCode(500);
        assertTrue(engine.getHistory().stream()
                .anyMatch(record -> "io.quarkiverse.goblin.it.SampleService.hello".equals(record.method())),
                "a targeted entry point is still assaulted, got: " + engine.getHistory());
    }

    /**
     * Excludes the package of {@code ExcludedResource} only.
     */
    public static class ExcludedEntryPointProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.target.exclude-packages", "io.quarkiverse.goblin.it.excluded");
        }
    }
}
