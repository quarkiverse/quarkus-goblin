package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultConfigChange;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.GoblinScenarios;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.dev.GoblinJsonRPCService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Issue #49: a scenario saved through the JSON-RPC service (the Dev UI and Dev MCP surface) loads back the exact
 * configuration, as one configuration change, and the next request is assaulted accordingly.
 */
@QuarkusTest
class GoblinScenariosIntegrationTest {

    @Inject
    GoblinJsonRPCService jsonRpc;

    @Inject
    AssaultEngine engine;

    @Inject
    RecordingConfigObserver recorder;

    private Path directory;

    @BeforeEach
    void useTemporaryStore() throws IOException {
        directory = Files.createTempDirectory("goblin-it-scenarios");
        System.setProperty(GoblinScenarios.DIRECTORY_PROPERTY, directory.toString());
        engine.setActive(true);
        engine.getMutableConfig().resetToDefaults();
        engine.getMutableConfig().setLatencyEnabled(false);
        engine.clearHistory();
        recorder.clear();
    }

    @AfterEach
    void cleanUp() throws IOException {
        System.clearProperty(GoblinScenarios.DIRECTORY_PROPERTY);
        engine.getMutableConfig().resetToDefaults();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void saveThenLoadRestoresTheExactConfigurationAndTheNextRequestIsAssaulted() {
        jsonRpc.applyConfig(Map.of(
                "latencyEnabled", false,
                "httpStatusEnabled", true,
                "httpStatus", Map.of("code", 418, "message", "I'm a teapot (scenario)"),
                "level", 100,
                "layers", List.of("HTTP_IN")));
        JsonObject expected = jsonRpc.getConfig();

        JsonObject saved = jsonRpc.saveScenario("teapot storm", false);
        assertTrue(saved.getBoolean("ok"), saved.encode());
        assertEquals("teapot storm", saved.getString("scenario"), "'scenario' is the stored name");
        assertEquals("httpStatus enabled (418: \"I'm a teapot (scenario)\")",
                saved.getJsonObject("saved").getString("assaults"));

        jsonRpc.resetDefaults();
        jsonRpc.setTargetLevel(5);
        recorder.clear();

        JsonObject loaded = jsonRpc.loadScenario("teapot storm");
        assertTrue(loaded.getBoolean("ok"), loaded.encode());
        assertEquals("teapot storm", loaded.getString("scenario"));
        assertEquals(expected, jsonRpc.getConfig(), "the loaded configuration is exactly the saved one");
        assertEquals(1, recorder.changes().size(), "loading a scenario is a single configuration change");
        AssaultConfigChange change = recorder.changes().getFirst();
        assertEquals(5, change.previous().getTargetLevel());
        assertEquals(100, change.current().getTargetLevel());

        RestAssured.given().get("/api/hello").then().statusCode(418);
    }

    @Test
    void scenariosAreListedForAnAgentBeforeAnythingIsEnabled() {
        jsonRpc.applyConfig(Map.of("latencyEnabled", true, "latency", Map.of("minMilliseconds", 300, "maxMilliseconds", 500),
                "level", 50, "layers", List.of("SERVICE")));
        jsonRpc.saveScenario("slow kitchen", false);

        JsonObject listed = jsonRpc.listScenarios();

        assertTrue(listed.getBoolean("ok"), listed.encode());
        JsonArray scenarios = listed.getJsonArray("scenarios");
        assertEquals(1, scenarios.size());
        JsonObject scenario = scenarios.getJsonObject(0);
        assertEquals("slow kitchen", scenario.getString("name"));
        assertEquals("latency enabled (300 - 500 ms)", scenario.getString("assaults"));
        assertEquals(List.of("SERVICE"), scenario.getJsonArray("layers").getList());
        assertEquals(50, scenario.getInteger("level"));
    }

    @Test
    void loadingAScenarioNeverSwitchesChaosOn() {
        jsonRpc.saveScenario("quiet", false);
        jsonRpc.setActive(false);

        jsonRpc.loadScenario("quiet");

        assertFalse(engine.isActive(), "a scenario holds the configuration only, never the active flag");
    }

    @Test
    void invalidMissingAndDuplicateScenariosAreRejected() {
        JsonObject invalid = jsonRpc.saveScenario("../escape", false);
        assertFalse(invalid.getBoolean("ok"));
        assertTrue(invalid.getString("error").startsWith("Invalid scenario name"), invalid.encode());
        assertEquals("INVALID_NAME", invalid.getString("code"));

        JsonObject missing = jsonRpc.loadScenario("nowhere");
        assertFalse(missing.getBoolean("ok"));
        assertTrue(missing.getString("error").contains("does not exist"), missing.encode());
        assertEquals("NOT_FOUND", missing.getString("code"));

        jsonRpc.saveScenario("twice", false);
        JsonObject duplicate = jsonRpc.saveScenario("TWICE", false);
        assertFalse(duplicate.getBoolean("ok"));
        assertEquals("EXISTS", duplicate.getString("code"), "the Dev UI confirms on the store's verdict, whatever the case");
        assertEquals("twice", duplicate.getString("existingScenario"), "the conflict names the stored scenario");
        assertFalse(duplicate.containsKey("scenario"), "'scenario' is only ever set on success");
        assertTrue(jsonRpc.saveScenario("twice", true).getBoolean("ok"));
    }

    @Test
    void deletingAScenarioRemovesItsFile() {
        jsonRpc.saveScenario("short-lived", false);
        assertTrue(Files.exists(directory.resolve("short-lived.json")));

        JsonObject deleted = jsonRpc.deleteScenario("short-lived");

        assertTrue(deleted.getBoolean("ok"));
        assertTrue(deleted.getBoolean("deleted"));
        assertFalse(Files.exists(directory.resolve("short-lived.json")));
        assertEquals(0, jsonRpc.listScenarios().getJsonArray("scenarios").size());
        MutableAssaultConfig current = engine.getMutableConfig();
        assertFalse(current.isHttpStatusEnabled(), "deleting a scenario leaves the current configuration untouched");
    }
}
