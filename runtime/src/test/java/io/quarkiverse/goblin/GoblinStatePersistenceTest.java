package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void loadReturnsDefaultsWhenFileIsCorrupted() throws IOException {
        Files.write(stateFile, "this is not json at all".getBytes());

        MutableAssaultConfig loaded = GoblinStatePersistence.load();
        assertNotNull(loaded);
        assertTrue(loaded.isLatencyEnabled());
        assertEquals(100, loaded.getTargetLevel());
    }

    @Test
    void saveHandlesDirectoryAsFile() throws IOException {
        Files.createDirectories(stateFile);

        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> GoblinStatePersistence.save(config));
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
    void parseJsonHandlesMultipleCommasInValue() {
        String json = "{\"key\": \"a, b, c, d\"}";
        var map = GoblinStatePersistence.parseJson(json);

        assertEquals("a, b, c, d", map.get("key"));
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
}
