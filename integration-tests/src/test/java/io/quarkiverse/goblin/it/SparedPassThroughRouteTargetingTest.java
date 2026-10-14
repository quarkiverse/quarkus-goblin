package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * {@code exclude-annotations} spares the annotated method only (issue #64): a pass-through {@code @Route} it spares
 * leaves the REST resource behind it targeted, with the same HTTP_IN draw.
 */
@QuarkusTest
@TestProfile(SparedPassThroughRouteTargetingTest.SparedProfile.class)
class SparedPassThroughRouteTargetingTest {

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(418);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    void theResourceBehindASparedPassThroughRouteIsAssaulted() {
        RestAssured.given().get("/api/spared/hello").then().statusCode(418);

        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertEquals(1, records.size(), "got: " + records);
        assertEquals("SampleResource.sparedHello", records.get(0).method());
    }

    @Test
    void theSparedRouteStillRuns() {
        engine.getMutableConfig().setHttpStatusEnabled(false);

        RestAssured.given().get("/api/spared/hello").then().statusCode(200)
                .header("X-Goblin-Spared", "seen")
                .body(equalTo("hello behind a spared pass-through route"));
    }

    /**
     * Spares the {@code @NonBlocking} pass-through route only.
     */
    public static class SparedProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.target.exclude-annotations", "io.smallrye.common.annotation.NonBlocking");
        }
    }
}
