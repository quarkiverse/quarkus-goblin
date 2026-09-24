package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * Verifies the test-mode default: without {@code quarkus.goblin.test.enabled}, an application's {@code @QuarkusTest}
 * suite is never assaulted, even with a live assault configured at level 100.
 */
@QuarkusTest
@TestProfile(TestModeWithoutOptInTest.NoTestOptInProfile.class)
class TestModeWithoutOptInTest {

    @Inject
    AssaultEngine engine;

    @Test
    void chaosIsInactiveInTestModeByDefault() {
        assertFalse(engine.isActive(), "chaos must stay off in test mode unless quarkus.goblin.test.enabled is set");

        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);

        assertTrue(engine.getHistory().isEmpty(), "no assault may fire, got: " + engine.getHistory());
    }

    /**
     * Removes the opt-in the integration-test application declares, restoring the extension default.
     */
    public static class NoTestOptInProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.test.enabled", "false");
        }
    }
}
