package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * Verifies that {@code quarkus.goblin.target.exclude-packages} takes precedence over
 * {@code quarkus.goblin.target.include-packages} when the same package matches both lists.
 */
@QuarkusTest
@TestProfile(ExcludeOverridesIncludeTargetingTest.ExcludeOverridesIncludeProfile.class)
class ExcludeOverridesIncludeTargetingTest extends AbstractPackageTargetingTest {

    /**
     * With the package both included and excluded, exclusion wins: the endpoint must not be assaulted and must return
     * 200.
     */
    @Test
    void testExcludeOverridesInclude() {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello from Goblin test app"));
    }

    /**
     * Test profile that includes and excludes the same package.
     */
    public static class ExcludeOverridesIncludeProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.goblin.target.include-packages", "io.quarkiverse.goblin.it",
                    "quarkus.goblin.target.exclude-packages", "io.quarkiverse.goblin.it");
        }
    }
}