package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoblinStatePersistenceTest {

    private Path tempDir;
    private Path stateFile;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("goblin-state-test");
        stateFile = tempDir.resolve("goblin-state.json");
        GoblinStatePersistence.overrideStateFile(stateFile.toString());
    }

    @AfterEach
    void cleanup() throws IOException {
        GoblinStatePersistence.overrideStateFile(null);
        Files.deleteIfExists(stateFile);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void saveAndLoad() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);
        config.setLatencyMinMs(200);
        config.setLatencyMaxMs(800);
        config.setExceptionType("java.io.IOException");
        config.setExceptionMessage("connection refused");
        config.setHttpStatusCode(429);
        config.setHttpStatusMessage("Too Many Requests");
        config.setTargetLevel(42);
        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);

        GoblinStatePersistence.save(config);

        assertTrue(Files.exists(stateFile));

        MutableAssaultConfig loaded = GoblinStatePersistence.load();
        assertNotNull(loaded);
        assertFalse(loaded.isLatencyEnabled());
        assertTrue(loaded.isExceptionEnabled());
        assertFalse(loaded.isHttpStatusEnabled());
        assertFalse(loaded.isDependencyDegradationEnabled());
        assertTrue(loaded.isClientLatencyEnabled());
        assertTrue(loaded.isClientExceptionEnabled());
        assertEquals(200, loaded.getLatencyMinMs());
        assertEquals(800, loaded.getLatencyMaxMs());
        assertEquals("java.io.IOException", loaded.getExceptionType());
        assertEquals("connection refused", loaded.getExceptionMessage());
        assertEquals(429, loaded.getHttpStatusCode());
        assertEquals("Too Many Requests", loaded.getHttpStatusMessage());
        assertEquals(42, loaded.getTargetLevel());
    }

    @Test
    void loadReturnsNullWhenFileDoesNotExist() {
        assertNull(GoblinStatePersistence.load());
    }

    @Test
    void loadFallsBackToTheConfigurationWhenFileIsCorrupted() throws IOException {
        Files.write(stateFile, "this is not json at all".getBytes());

        assertNull(GoblinStatePersistence.load(),
                "an unreadable state file must fall back to application.properties instead of failing the start");
    }

    @Test
    void saveHandlesDirectoryAsFile() throws IOException {
        Files.createDirectories(stateFile);

        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> GoblinStatePersistence.save(config));
    }

    @Test
    void saveAndLoadPreservesLayers() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayers(List.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN));

        GoblinStatePersistence.save(config);
        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertEquals(Set.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN), loaded.getLayers());
        assertFalse(loaded.isLayerEnabled(ChaosLayer.HTTP_OUT), "HTTP_OUT must not be re-armed by the save/load round-trip");
    }

    @Test
    void fromJsonRestoresLayers() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"layers\": \"SERVICE,HTTP_IN\"}");

        assertEquals(Set.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN), config.getLayers());
    }

    @Test
    void fromJsonDefaultsLayersWhenMissing() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"latencyEnabled\": false}");

        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());
    }

    @Test
    void fromJsonSkipsUnknownLayerLabels() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"layers\": \"DATABASE,BOGUS,HTTP_IN\"}");

        assertTrue(config.isLayerEnabled(ChaosLayer.DATABASE));
        assertTrue(config.isLayerEnabled(ChaosLayer.HTTP_IN));
        assertFalse(config.isLayerEnabled(ChaosLayer.HTTP_OUT));
    }

    @Test
    void fromJsonToleratesLayerLabelCaseAndWhitespace() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"layers\": \" service , HTTP_IN \"}");

        assertEquals(Set.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN), config.getLayers());
    }

    @Test
    void fromJsonDefaultsLayersWhenOnlyUnknownLabels() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"layers\": \"BOGUS,WHATEVER\"}");

        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());
    }

    @Test
    void fromJsonHandlesMissingFields() {
        String json = "{\"latencyEnabled\": false}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertNotNull(config);
        assertFalse(config.isLatencyEnabled());
        assertFalse(config.isClientLatencyEnabled());
        assertFalse(config.isClientExceptionEnabled());
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(5000, config.getLatencyMaxMs());
        assertEquals("java.lang.RuntimeException", config.getExceptionType());
        assertEquals(503, config.getHttpStatusCode());
        assertEquals(100, config.getTargetLevel());
    }

    @Test
    void fromJsonHandlesEmptyObject() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{}");

        assertNotNull(config);
        assertTrue(config.isLatencyEnabled());
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(100, config.getTargetLevel());
    }

    @Test
    void fromJsonHandlesQuotedCommasInValues() {
        String json = "{\"exceptionMessage\": \"Error: invalid, request\", \"httpStatusMessage\": \"Service\\nUnavailable\"}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertNotNull(config);
        assertEquals("Error: invalid, request", config.getExceptionMessage());
        assertEquals("Service\nUnavailable", config.getHttpStatusMessage());
    }

    @Test
    void fromJsonHandlesEscapedQuotesInValues() {
        String json = "{\"exceptionMessage\": \"Say \\\"hello\\\"\"}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertNotNull(config);
        assertEquals("Say \"hello\"", config.getExceptionMessage());
    }

    @Test
    void saveAndLoadPreservesEscapedStrings() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setExceptionMessage("Error: invalid, request");
        config.setHttpStatusMessage("Service\nUnavailable");

        GoblinStatePersistence.save(config);
        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertEquals("Error: invalid, request", loaded.getExceptionMessage());
        assertEquals("Service\nUnavailable", loaded.getHttpStatusMessage());
    }

    @Test
    void saveAndLoadPreservesProfileAndOverrides() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setProfile(AssaultProfile.SLOW_FAILURE);
        config.setExceptionEnabled(false);

        GoblinStatePersistence.save(config);
        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertEquals(AssaultProfile.SLOW_FAILURE, loaded.getProfile());
        assertTrue(loaded.isLatencyEnabled());
        assertFalse(loaded.isExceptionEnabled());
    }

    @Test
    void fromJsonRestoresProfileLabelWithoutApplyingDefaults() {
        String json = "{\"profile\": \"SLOW_FAILURE\", \"latencyEnabled\": false, \"exceptionEnabled\": true}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertEquals(AssaultProfile.SLOW_FAILURE, config.getProfile());
        assertFalse(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
    }

    @Test
    void fromJsonDefaultsProfileToNoneWhenMissing() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"latencyEnabled\": true}");

        assertEquals(AssaultProfile.NONE, config.getProfile());
    }

    @Test
    void fromJsonHandlesUnknownProfileByDefaultingToNone() {
        String json = "{\"profile\": \"NOT_A_PROFILE\"}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertEquals(AssaultProfile.NONE, config.getProfile());
    }

    @Test
    void fromJsonHandlesProfileCaseAndWhitespace() {
        String json = "{\"profile\": \" slow_failure \", \"latencyEnabled\": \" false \", \"exceptionEnabled\": \"true \"}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertEquals(AssaultProfile.SLOW_FAILURE, config.getProfile());
        assertFalse(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
    }

    @Test
    void savedJsonDoesNotContainActiveFlag() throws IOException {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(true);
        GoblinStatePersistence.save(config);
        String content = Files.readString(stateFile);
        assertFalse(content.contains("\"active\""), "active flag must not be persisted");
    }

    @Test
    void fromJsonIgnoresLegacyActiveField() {
        String json = "{\"active\": true, \"latencyEnabled\": false}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);
        assertFalse(config.isLatencyEnabled());
    }

    @Test
    void saveAndLoadPreservesResponseBodyConfig() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(200);

        GoblinStatePersistence.save(config);
        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertTrue(loaded.isResponseBodyEnabled());
        assertEquals(ResponseBodyMode.INFLATE, loaded.getResponseBodyMode());
        assertEquals(200, loaded.getResponseBodyPercentage());
    }

    @Test
    void fromJsonRestoresResponseBodyFieldsWithDefaults() {
        String json = "{\"responseBodyEnabled\": true, \"responseBodyMode\": \"inflate\", \"responseBodyPercentage\": 180}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertTrue(config.isResponseBodyEnabled());
        assertEquals(ResponseBodyMode.INFLATE, config.getResponseBodyMode());
        assertEquals(180, config.getResponseBodyPercentage());
    }

    @Test
    void saveAndLoadPreservesResponseHeaderConfig() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeaderEnabled(true);
        config.setResponseHeader("X-Added", ResponseHeaderAction.SET, "Say \"hi\", ok");
        config.setResponseHeader("Server", ResponseHeaderAction.SET, "goblin");
        config.setResponseHeader("Content-Type", ResponseHeaderAction.REMOVE, "");

        GoblinStatePersistence.save(config);
        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertTrue(loaded.isResponseHeaderEnabled());
        assertEquals(3, loaded.getResponseHeaders().size());
        assertEquals("Say \"hi\", ok", loaded.getResponseHeaders().get("X-Added").value());
        assertEquals(ResponseHeaderAction.SET, loaded.getResponseHeaders().get("X-Added").action());
        assertEquals(ResponseHeaderAction.SET, loaded.getResponseHeaders().get("Server").action());
        assertEquals(ResponseHeaderAction.REMOVE, loaded.getResponseHeaders().get("Content-Type").action());
    }

    @Test
    void fromJsonDefaultsResponseHeaderWhenMissing() {
        MutableAssaultConfig config = GoblinStatePersistence.fromJson("{\"latencyEnabled\": false}");

        assertFalse(config.isResponseHeaderEnabled());
        assertTrue(config.getResponseHeaders().isEmpty());
    }

    @Test
    void loadSkipsResponseHeaderRuleWithUnknownAction() throws IOException {
        String inner = "{\"action\": \"BOGUS\", \"value\": \"chaos\"}";
        writeStateWithResponseHeaders("{\"X-Goblin\": \"" + escape(inner) + "\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertTrue(loaded.getResponseHeaders().isEmpty(),
                "an unknown action must be skipped instead of being restored as SET");
    }

    @Test
    void loadSkipsResponseHeaderRuleWithoutAction() throws IOException {
        String inner = "{\"value\": \"chaos\"}";
        writeStateWithResponseHeaders("{\"X-Goblin\": \"" + escape(inner) + "\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertTrue(loaded.getResponseHeaders().isEmpty());
    }

    @Test
    void loadSkipsResponseHeaderRuleThatIsNotANestedObject() throws IOException {
        writeStateWithResponseHeaders("{\"X-Goblin\": \"chaos\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertTrue(loaded.getResponseHeaders().isEmpty(),
                "a scalar entry must not be interpreted as a header rule");
    }

    @Test
    void loadSkipsResponseHeaderRuleWithUnsafeValue() throws IOException {
        // valid JSON whose decoded value contains a line feed (JSON escape sequence, not a raw newline)
        String inner = "{\"action\": \"SET\", \"value\": \"chaos\\nInjected: true\"}";
        writeStateWithResponseHeaders("{\"X-Goblin\": \"" + escape(inner) + "\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertTrue(loaded.getResponseHeaders().isEmpty(),
                "a value containing CR/LF must not be restored");
    }

    private void writeStateWithResponseHeaders(String encodedHeaders) throws IOException {
        Files.writeString(stateFile, "{\n  \"responseHeaderEnabled\": true,\n  \"responseHeaders\": \""
                + escape(encodedHeaders) + "\"\n}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void loadMigratesLegacyAddAndOverrideActionsToSet() throws IOException {
        String added = "{\"action\": \"ADD\", \"value\": \"chaos\"}";
        String overridden = "{\"action\": \"OVERRIDE\", \"value\": \"goblin\"}";
        writeStateWithResponseHeaders("{\"X-Added\": \"" + escape(added) + "\", \"X-Override\": \""
                + escape(overridden) + "\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertEquals(ResponseHeaderAction.SET, loaded.getResponseHeaders().get("X-Added").action());
        assertEquals(ResponseHeaderAction.SET, loaded.getResponseHeaders().get("X-Override").action());
    }

    @Test
    void loadFallsBackWhenAValueIsNotNumeric() throws IOException {
        Files.writeString(stateFile, "{\"latencyMinMs\":\"abc\",\"targetLevel\":\"high\"}");

        assertDoesNotThrow(GoblinStatePersistence::load);
        assertNull(GoblinStatePersistence.load(), "an unreadable state must fall back to the static configuration");
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() throws IOException {
        GoblinStatePersistence.save(new MutableAssaultConfig());

        assertTrue(Files.exists(stateFile));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "only the state file must remain");
        }
    }

    @Test
    void savedStateIsStructuredJson() throws IOException {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayers(List.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN));
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");
        config.setTargetLevel(42);

        GoblinStatePersistence.save(config);
        io.vertx.core.json.JsonObject saved = new io.vertx.core.json.JsonObject(Files.readString(stateFile));

        assertEquals(42, saved.getInteger("targetLevel"), "numbers are written as JSON numbers");
        assertEquals(Boolean.TRUE, saved.getBoolean("latencyEnabled"), "booleans are written as JSON booleans");
        assertEquals(List.of("SERVICE", "HTTP_IN"), saved.getJsonArray("layers").getList());
        assertEquals("SET", saved.getJsonObject("responseHeaders").getJsonObject("X-Goblin").getString("action"));
    }

    @Test
    void legacyFlatStateFileIsStillReadable() throws IOException {
        String legacyHeaders = "{\"X-Goblin\": \"{\\\"action\\\": \\\"SET\\\", \\\"value\\\": \\\"chaos\\\"}\"}";
        Files.writeString(stateFile, "{\"latencyEnabled\": \"false\", \"targetLevel\": \"42\", "
                + "\"layers\": \"SERVICE,HTTP_IN\", \"responseHeaders\": \"" + escape(legacyHeaders) + "\"}");

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded);
        assertFalse(loaded.isLatencyEnabled(), "quoted booleans of the legacy format are accepted");
        assertEquals(42, loaded.getTargetLevel(), "quoted numbers of the legacy format are accepted");
        assertEquals(Set.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN), loaded.getLayers());
        assertEquals("chaos", loaded.getResponseHeaders().get("X-Goblin").value());
    }

    @Test
    void stateFileWrittenByTheLegacyWriterIsStillReadable() throws Exception {
        // verbatim file written by the hand-rolled writer of earlier versions: the header rules are pretty-printed JSON
        // embedded in a string with raw, unescaped line breaks
        try (var legacy = getClass().getResourceAsStream("/legacy-goblin-state.json")) {
            Files.write(stateFile, legacy.readAllBytes());
        }

        MutableAssaultConfig loaded = GoblinStatePersistence.load();

        assertNotNull(loaded, "a state file written by an earlier version must not be discarded");
        assertTrue(loaded.isExceptionEnabled());
        assertFalse(loaded.isLatencyEnabled());
        assertEquals(4000, loaded.getLatencyMaxMs());
        assertEquals(404, loaded.getHttpStatusCode());
        assertEquals(Set.of(ChaosLayer.DATABASE), loaded.getLayers());
        assertEquals("xxxxxxx", loaded.getResponseHeaders().get("X-token").value());
        assertEquals("Basic xxxxx", loaded.getResponseHeaders().get("Authentication").value());
    }

    @Test
    void controlCharactersAreOnlyEscapedInsideStrings() {
        assertEquals("{\n  \"a\": \"x\\ny\"\n}",
                GoblinStatePersistence.escapeControlCharactersInStrings("{\n  \"a\": \"x\ny\"\n}"));
        assertEquals("{\"a\": \"q\\\"\\n\"}",
                GoblinStatePersistence.escapeControlCharactersInStrings("{\"a\": \"q\\\"\\n\"}"),
                "escape sequences already present are kept as they are");
    }
}
