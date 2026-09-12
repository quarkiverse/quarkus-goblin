package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/**
 * Verifies that {@code quarkus.goblin.target.exclude-packages} prevents chaos on endpoints whose package matches the
 * exclusion list.
 */
@QuarkusTest
@TestProfile(ExcludePackageTargetingTest.ExcludePackageProfile.class)
class ExcludePackageTargetingTest extends AbstractPackageTargetingTest {

    /**
     * With the whole integration test package excluded and the HTTP status assault enabled, the endpoint must not be
     * assaulted and must return 200.
     */
    @Test
    void testExcludedPackageBlocksChaos() {
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello from Goblin test app"));
    }

    /**
     * Test profile excluding the integration test package from chaos.
     */
    public static class ExcludePackageProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.goblin.target.exclude-packages", "io.quarkiverse.goblin.it");
        }
    }
}