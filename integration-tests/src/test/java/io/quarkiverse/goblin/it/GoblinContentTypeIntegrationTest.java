package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/**
 * Issue #69: the HTTP status and dependency degradation assaults must answer with a body valid for
 * their declared Content-Type, in the representation the endpoint answers when it is not assaulted: JSON resources --
 * with or without {@code @Produces} -- get parseable JSON, text resources and entity-less endpoints get text/plain.
 */
@QuarkusTest
public class GoblinContentTypeIntegrationTest {

    @Inject
    AssaultEngine engine;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(200);
        engine.clearHistory();
    }

    @Test
    public void httpStatusOnJsonPojoIsParseableJson() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (Goblin chaos)");

        RestAssured.given()
                .get("/api/chaos-content/pojo")
                .then()
                .statusCode(503)
                .contentType(containsString(ContentType.JSON.toString()))
                .body("message", equalTo("Service Unavailable (Goblin chaos)"))
                .body("code", equalTo(503));
    }

    @Test
    public void httpStatusOnTextStaysTextPlain() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (Goblin chaos)");

        String body = RestAssured.given()
                .get("/api/chaos-content/text")
                .then()
                .statusCode(503)
                .contentType(containsString(ContentType.TEXT.toString()))
                .extract().asString();

        assertEquals("Service Unavailable (Goblin chaos)", body);
    }

    @Test
    public void dependencyDegradationOnJsonIsParseableJson() {
        engine.setActive(true);
        engine.getMutableConfig().setDependencyDegradationEnabled(true);

        RestAssured.given()
                .get("/api/chaos-content/pojo")
                .then()
                .statusCode(503)
                .contentType(containsString(ContentType.JSON.toString()))
                .body("message", equalTo("Dependency unavailable (Goblin chaos)"))
                .body("code", equalTo(503));
    }

    @Test
    public void dependencyDegradationOnTextStaysTextPlain() {
        engine.setActive(true);
        engine.getMutableConfig().setDependencyDegradationEnabled(true);

        String body = RestAssured.given()
                .get("/api/chaos-content/text")
                .then()
                .statusCode(503)
                .contentType(containsString(ContentType.TEXT.toString()))
                .extract().asString();

        assertEquals("Dependency unavailable (Goblin chaos)", body);
    }

    /**
     * Every resource shape, assaulted: the abort keeps the representation the endpoint answers without chaos, and its
     * body is valid for the declared type.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/chaos-content/pojo, json",
            "/api/chaos-content/pojo-explicit, json",
            "/api/chaos-content/uni-pojo, json",
            "/api/chaos-content/explicit-json-string, json",
            "/api/chaos-content/string, text",
            "/api/chaos-content/text, text",
            "/api/chaos-content/no-entity, text"
    })
    public void httpStatusMatchesTheContractOfEveryResourceShape(String path, String expected) {
        engine.setActive(false);
        Response normal = RestAssured.given().get(path);
        if (normal.statusCode() != 204) {
            assertTrue(normal.contentType().startsWith(expected.equals("json") ? "application/json" : "text/plain"),
                    "the unassaulted endpoint answers " + normal.contentType() + ", the table is wrong for " + path);
        }

        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setHttpStatusMessage("Service Unavailable (Goblin chaos)");
        Response assaulted = RestAssured.given().get(path);

        assertEquals(503, assaulted.statusCode());
        if (expected.equals("json")) {
            assertTrue(assaulted.contentType().startsWith("application/json"), assaulted.contentType());
            JsonPath body = assaulted.jsonPath();
            assertEquals("Service Unavailable (Goblin chaos)", body.getString("message"));
            assertEquals(503, body.getInt("code"));
        } else {
            assertTrue(assaulted.contentType().startsWith("text/plain"), assaulted.contentType());
            assertEquals("Service Unavailable (Goblin chaos)", assaulted.asString());
        }
    }
}
