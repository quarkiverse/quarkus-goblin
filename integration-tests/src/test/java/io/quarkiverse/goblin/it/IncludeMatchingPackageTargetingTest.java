package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * Verifies that {@code quarkus.goblin.target.include-packages} allows chaos on endpoints whose package is included.
 */
@QuarkusTest
@TestProfile(IncludeMatchingPackageTargetingTest.IncludeMatchingProfile.class)
class IncludeMatchingPackageTargetingTest extends AbstractPackageTargetingTest {

    /**
     * With the endpoint's package listed in the include set and the HTTP status assault enabled, the request must be
     * short-circuited with 503.
     */
    @Test
    void testMatchingIncludePackageAllowsChaos() {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503)
                .body(equalTo("Service Unavailable (targeting test)"));
    }

    /**
     * Test profile that includes the integration test package.
     */
    public static class IncludeMatchingProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.target.include-packages", "io.quarkiverse.goblin.it");
        }
    }
}