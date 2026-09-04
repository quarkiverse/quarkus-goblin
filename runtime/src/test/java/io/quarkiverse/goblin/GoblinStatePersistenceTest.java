package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GoblinStatePersistenceTest {

    private static final String STATE_FILE = ".goblin-state.json";

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(Path.of(STATE_FILE));
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

        GoblinStatePersistence.save(config);

        assertTrue(Files.exists(Path.of(STATE_FILE)));

        MutableAssaultConfig loaded = GoblinStatePersistence.load();
        assertNotNull(loaded);
        assertFalse(loaded.isLatencyEnabled());
        assertTrue(loaded.isExceptionEnabled());
        assertFalse(loaded.isHttpStatusEnabled());
        assertFalse(loaded.isDependencyDegradationEnabled());
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
        Files.write(Path.of(STATE_FILE), new byte[] { 0x00, 0x01, 0x02 });

        MutableAssaultConfig loaded = GoblinStatePersistence.load();
        assertNotNull(loaded);
        assertTrue(loaded.isLatencyEnabled());
        assertEquals(100, loaded.getTargetLevel());
    }

    @Test
    void saveHandlesDirectoryAsFile() throws IOException {
        Files.createDirectories(Path.of(STATE_FILE));

        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> GoblinStatePersistence.save(config));

        Files.deleteIfExists(Path.of(STATE_FILE));
    }

    @Test
    void fromJsonHandlesMissingFields() {
        String json = "{\"latencyEnabled\": false}";
        MutableAssaultConfig config = GoblinStatePersistence.fromJson(json);

        assertNotNull(config);
        assertFalse(config.isLatencyEnabled());
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
}
