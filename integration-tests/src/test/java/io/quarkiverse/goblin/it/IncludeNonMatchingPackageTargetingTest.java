package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * Verifies that {@code quarkus.goblin.target.include-packages} limits chaos to the matching packages: an endpoint in a
 * non-matching package must be spared.
 */
@QuarkusTest
@TestProfile(IncludeNonMatchingPackageTargetingTest.IncludeNonMatchingProfile.class)
class IncludeNonMatchingPackageTargetingTest extends AbstractPackageTargetingTest {

    /**
     * With an include list that does not match the endpoint's package, the endpoint must not be assaulted and must
     * return 200.
     */
    @Test
    void testNonMatchingIncludePackageBlocksChaos() {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello from Goblin test app"));
    }

    /**
     * Test profile that only includes a package the sample endpoints do not belong to.
     */
    public static class IncludeNonMatchingProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.target.include-packages", "com.example.other");
        }
    }
}