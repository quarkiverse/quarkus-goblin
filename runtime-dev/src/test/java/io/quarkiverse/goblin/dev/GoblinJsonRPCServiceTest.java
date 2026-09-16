package io.quarkiverse.goblin.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultProfile;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.vertx.core.json.JsonObject;

class GoblinJsonRPCServiceTest {

    private GoblinJsonRPCService service;
    private AssaultEngine engine;

    @BeforeEach
    void setUp() {
        service = new GoblinJsonRPCService();
        engine = new AssaultEngine();
        service.engine = engine;
    }

    /**
     * A null {@link MutableAssaultConfig} (engine not yet initialised) must produce a stable {@code ok=false} error
     * object instead of throwing.
     */
    @Test
    void setProfileReturnsOkFalseWhenEngineNotInitialised() {
        JsonObject result = service.setProfile("SLOW_FAILURE");

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    /**
     * A valid profile name must return {@code ok=true} with the full configuration shape, matching case-insensitively.
     */
    @Test
    void setProfileReturnsOkTrueAndConfigShapeOnSuccess() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setProfile("slow_failure");

        assertTrue(result.getBoolean("ok"));
        assertEquals(AssaultProfile.SLOW_FAILURE.name(), result.getString("profile"));
        assertTrue(result.getBoolean("latencyEnabled"));
        assertTrue(result.getBoolean("exceptionEnabled"));
        assertNotNull(result.getJsonObject("latency"));
        assertNotNull(result.getJsonObject("exception"));
        assertNotNull(result.getJsonObject("httpStatus"));
        assertTrue(result.containsKey("level"));
    }

    /**
     * A blank profile name must reset to {@link AssaultProfile#NONE} and return {@code ok=true}.
     */
    @Test
    void setProfileResetsToNoneWhenBlank() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setProfile("  ");

        assertTrue(result.getBoolean("ok"));
        assertEquals(AssaultProfile.NONE.name(), result.getString("profile"));
    }

    /**
     * An unrecognised profile name must produce a stable {@code ok=false} error object instead of throwing.
     */
    @Test
    void setProfileReturnsOkFalseForUnknownProfile() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setProfile("NOT_A_PROFILE");

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    /**
     * The config and status payloads must expose the client-side assault toggles.
     */
    @Test
    void clientTogglesExposedInConfigAndStatus() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = service.getConfig();
        assertTrue(config.containsKey("clientLatencyEnabled"));
        assertTrue(config.containsKey("clientExceptionEnabled"));

        JsonObject status = service.getStatus();
        assertTrue(status.containsKey("clientLatencyEnabled"));
        assertTrue(status.containsKey("clientExceptionEnabled"));
    }

    /**
     * {@code toggleClientLatency} flips the client latency toggle and reports the new value.
     */
    @Test
    void toggleClientLatencyFlipsValue() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleClientLatency();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("clientLatencyEnabled"));

        JsonObject again = service.toggleClientLatency();
        assertFalse(again.getBoolean("clientLatencyEnabled"));
    }

    /**
     * {@code toggleClientException} flips the client exception toggle and reports the new value.
     */
    @Test
    void toggleClientExceptionFlipsValue() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleClientException();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("clientExceptionEnabled"));

        JsonObject again = service.toggleClientException();
        assertFalse(again.getBoolean("clientExceptionEnabled"));
    }

    /**
     * Injects a {@link MutableAssaultConfig} into the engine's private field so the service can be exercised without a
     * full CDI/Quarkus runtime.
     *
     * @param config the configuration to install
     */
    private void setMutableConfig(MutableAssaultConfig config) {
        try {
            Field field = AssaultEngine.class.getDeclaredField("mutableConfig");
            field.setAccessible(true);
            field.set(engine, config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inject mutable config into engine", e);
        }
    }
}
