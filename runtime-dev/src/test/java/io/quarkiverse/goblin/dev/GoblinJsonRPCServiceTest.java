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

    private static final String[] TOGGLE_KEYS = {
            "latencyEnabled", "exceptionEnabled", "httpStatusEnabled", "dependencyDegradationEnabled",
            "clientLatencyEnabled", "clientExceptionEnabled", "responseBodyEnabled",
    };

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
        assertEquals(MutableAssaultConfig.EXCEPTION_PRESETS, result.getJsonArray("exceptionPresets").getList(),
                "the full config must expose the server-side exception presets");
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
     * The config and status payloads must expose the response body assault toggle and parameters.
     */
    @Test
    void responseBodyExposedInConfigAndStatus() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = service.getConfig();
        assertTrue(config.containsKey("responseBodyEnabled"));
        JsonObject body = config.getJsonObject("body");
        assertNotNull(body);
        assertEquals("TRUNCATE", body.getString("mode"));
        assertEquals(50, body.getInteger("percentage"));

        JsonObject status = service.getStatus();
        assertTrue(status.containsKey("responseBodyEnabled"));
    }

    /**
     * {@code toggleResponseBody} flips the response body toggle and reports the new value.
     */
    @Test
    void toggleResponseBodyFlipsValue() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleResponseBody();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseBodyEnabled"));

        JsonObject again = service.toggleResponseBody();
        assertFalse(again.getBoolean("responseBodyEnabled"));
    }

    /**
     * {@code setResponseBodyConfig} applies the mode and percentage and reports them back.
     */
    @Test
    void setResponseBodyConfigAppliesValues() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseBodyConfig("inflate", 175);

        assertTrue(result.getBoolean("ok"));
        assertEquals("INFLATE", result.getString("mode"));
        assertEquals(175, result.getInteger("percentage"));
        assertFalse(service.getConfig().getBoolean("responseBodyEnabled"), "config does not enable the toggle");
    }

    /**
     * {@code setResponseBodyConfig} rejects an unknown mode with a stable error object.
     */
    @Test
    void setResponseBodyConfigRejectsUnknownMode() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseBodyConfig("SHRINK", 50);

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    /**
     * {@code setResponseBodyConfig} clamps an invalid percentage and surfaces a warning.
     */
    @Test
    void setResponseBodyConfigWarnsWhenPercentageClamped() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseBodyConfig("TRUNCATE", 250);

        assertTrue(result.getBoolean("ok"));
        assertEquals(100, result.getInteger("percentage"));
        assertTrue(result.containsKey("warning"));
    }

    /**
     * Mutations return the full configuration so the Dev UI can use a single source of truth.
     */
    @Test
    void assertionMutationsReturnFullConfig() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleLatency();
        assertTrue(result.getBoolean("ok"));
        assertEquals("NONE", result.getString("profile"));
        assertTrue(result.containsKey("latency"));
        assertTrue(result.containsKey("exception"));
        assertTrue(result.containsKey("httpStatus"));
        assertTrue(result.containsKey("body"));
        assertTrue(result.containsKey("level"));
        assertEquals("NONE", service.getConfig().getString("profile"));
    }

    /**
     * The toggles must be exposed top-level in every mutation result so the existing Dev UI handlers keep working.
     */
    @Test
    void toggleResultsExposeEveryToggle() throws Exception {
        MutableAssaultConfig config = setMutableConfig(new MutableAssaultConfig());
        config.setLatencyEnabled(false);

        JsonObject latency = service.toggleLatency();
        for (String key : TOGGLE_KEYS) {
            assertTrue(latency.containsKey(key), "toggle result must expose " + key);
        }
        assertFalse(latency.getBoolean("clientLatencyEnabled"));
        assertTrue(latency.getBoolean("latencyEnabled"));
    }

    /**
     * {@code setLatencyRange} reports the values both at the top level and nested in the full config.
     */
    @Test
    void setLatencyRangeReturnsFullConfig() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setLatencyRange(150, 300);

        assertTrue(result.getBoolean("ok"));
        assertEquals(150, result.getInteger("minMilliseconds"));
        assertEquals(300, result.getInteger("maxMilliseconds"));
        assertEquals(150, result.getJsonObject("latency").getInteger("minMilliseconds"));
        assertEquals(300, result.getJsonObject("latency").getInteger("maxMilliseconds"));
    }

    /**
     * {@code toggleActive} flips the engine state and returns the full configuration plus the effective {@code active}
     * flag, mirroring the single-source-of-truth contract of every mutation.
     */
    @Test
    void toggleActiveReturnsFullConfigAndEffectiveActiveFlag() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleActive();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("active"));
        assertTrue(engine.isActive());
        assertTrue(result.containsKey("profile"));
        assertEquals(MutableAssaultConfig.EXCEPTION_PRESETS, result.getJsonArray("exceptionPresets").getList());
    }

    /**
     * {@code setActive} returns the full configuration with the requested {@code active} flag.
     */
    @Test
    void setActiveReturnsFullConfigAndActiveFlag() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setActive(false);

        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("active"));
        assertFalse(engine.isActive());
        assertEquals(MutableAssaultConfig.EXCEPTION_PRESETS, result.getJsonArray("exceptionPresets").getList());
    }

    /**
     * The kill switch deactivates the engine, disables every assault and resets the profile.
     */
    @Test
    void disableAllTurnsEverythingOff() throws Exception {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(true);
        config.setExceptionEnabled(true);
        config.setClientLatencyEnabled(true);
        engine.setActive(true);
        setMutableConfig(config);

        JsonObject result = service.disableAll();

        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("active"));
        assertFalse(engine.isActive());
        for (String key : TOGGLE_KEYS) {
            assertFalse(result.getBoolean(key), key + " must be disabled");
        }
        assertEquals("NONE", result.getString("profile"));
    }

    /**
     * {@code resetDefaults} restores every parameter and toggle to its default, keeping {@code ok=true}.
     */
    @Test
    void resetDefaultsRestoresTheDefaults() throws Exception {
        MutableAssaultConfig config = setMutableConfig(new MutableAssaultConfig());
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);
        config.setHttpStatusEnabled(true);
        config.setResponseBodyEnabled(true);
        config.setTargetLevel(30);

        JsonObject result = service.resetDefaults();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("latencyEnabled"), "latency is the default assault");
        assertFalse(result.getBoolean("exceptionEnabled"));
        assertFalse(result.getBoolean("httpStatusEnabled"));
        assertFalse(result.getBoolean("responseBodyEnabled"));
        assertEquals(100, result.getInteger("level"));
        assertEquals(50, result.getJsonObject("body").getInteger("percentage"));

        JsonObject defaults = service.getConfig();
        assertEquals(503, defaults.getJsonObject("httpStatus").getInteger("code"));
        assertEquals(5000, defaults.getJsonObject("latency").getInteger("maxMilliseconds"));
    }

    /**
     * {@code applyConfig} applies a partial configuration and leaves untouched fields at their current value.
     */
    @Test
    void applyConfigAppliesOnlyTheProvidedFields() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject partial = new JsonObject()
                .put("exceptionEnabled", true)
                .put("level", 42)
                .put("exception", new JsonObject().put("type", "java.io.IOException"));

        JsonObject result = service.applyConfig(partial);

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("exceptionEnabled"));
        assertEquals(42, result.getInteger("level"));
        assertEquals("java.io.IOException", result.getJsonObject("exception").getString("type"));
        assertEquals(100, result.getJsonObject("latency").getInteger("minMilliseconds"),
                "omitted latency parameters must keep their value");
        String warning = result.getString("warning");
        assertNotNull(warning, "a checked exception must surface a warning");
        assertTrue(warning.contains("does not extend RuntimeException"), "unexpected warning: " + warning);
    }

    /**
     * {@code applyConfig} applies the profile first and lets the remaining fields override its defaults.
     */
    @Test
    void applyConfigAppliesProfileThenOverrides() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = new JsonObject()
                .put("profile", "SLOW_FAILURE")
                .put("latencyEnabled", false);

        JsonObject result = service.applyConfig(config);

        assertTrue(result.getBoolean("ok"));
        assertEquals("SLOW_FAILURE", result.getString("profile"));
        assertTrue(result.getBoolean("exceptionEnabled"), "profile defaults apply");
        assertFalse(result.getBoolean("latencyEnabled"), "explicit field overrides the profile default");
    }

    /**
     * The counters endpoint reports the engine totals and per-type breakdown.
     */
    @Test
    void getCountersReportsTotalsAndPerType() throws Exception {
        setMutableConfig(new MutableAssaultConfig());
        engine.recordAssault("a", "latency");
        engine.recordAssault("b", "response-body-truncate");

        JsonObject counters = service.getCounters();

        assertEquals(2, counters.getInteger("total"));
        assertEquals(1L, counters.getJsonObject("byType").getLong("latency"));
        assertEquals(1L, counters.getJsonObject("byType").getLong("response-body-truncate"));
        assertTrue(counters.containsKey("since"));
    }

    /**
     * {@code resetCounters} clears the engine counters.
     */
    @Test
    void resetCountersClearsCounts() throws Exception {
        setMutableConfig(new MutableAssaultConfig());
        engine.recordAssault("a", "latency");

        JsonObject result = service.resetCounters();

        assertTrue(result.getBoolean("ok"));
        assertEquals(0, engine.getTotalAssaultCount());
    }

    /**
     * {@code setExceptionConfig} accepts a checked exception like {@code TimeoutException} (it cannot be thrown by the
     * filter layer) but must surface a warning so the Dev UI user knows the engine will fall back to
     * {@code RuntimeException}.
     */
    @Test
    void setExceptionConfigWarnsOnCheckedException() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setExceptionConfig("java.util.concurrent.TimeoutException", "boom");

        assertTrue(result.getBoolean("ok"));
        assertEquals("java.util.concurrent.TimeoutException", result.getString("type"));
        String warning = result.getString("warning");
        assertNotNull(warning, "a checked exception must surface a warning");
        assertTrue(warning.contains("does not extend RuntimeException"), "unexpected warning: " + warning);
    }

    /**
     * {@code setExceptionConfig} keeps a {@code RuntimeException} subtype without warnings.
     */
    @Test
    void setExceptionConfigAcceptsRuntimeExceptionSubtype() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setExceptionConfig("jakarta.ws.rs.WebApplicationException", "boom");

        assertTrue(result.getBoolean("ok"));
        assertEquals("jakarta.ws.rs.WebApplicationException", result.getString("type"));
        assertTrue(result.getString("warning").isBlank(),
                "a RuntimeException subtype must not surface a warning: " + result.getString("warning"));
    }

    /**
     * Every server-side exception preset must be accepted without a fallback warning, mirroring the guarantee that the
     * Dev UI quick picks can always be thrown for real.
     */
    @Test
    void everyExceptionPresetIsAcceptedWithoutFallbackWarning() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        for (String preset : MutableAssaultConfig.EXCEPTION_PRESETS) {
            JsonObject result = service.setExceptionConfig(preset, "boom");
            assertTrue(result.getBoolean("ok"), "preset must be accepted: " + preset);
            assertEquals(preset, result.getString("type"));
            String warning = result.getString("warning");
            assertFalse(warning != null && warning.contains("fall back to RuntimeException"),
                    "preset must not surface a fallback warning: " + preset + " -> " + warning);
        }
    }

    /**
     * {@code getConfig} exposes the server-side exception presets under {@code exceptionPresets}.
     */
    @Test
    void getConfigExposesServerSideExceptionPresets() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = service.getConfig();

        assertEquals(MutableAssaultConfig.EXCEPTION_PRESETS, config.getJsonArray("exceptionPresets").getList());
    }

    /**
     * Injects a {@link MutableAssaultConfig} into the engine's private field so the service can be exercised without a
     * full CDI/Quarkus runtime, and returns it for further mutation.
     *
     * @param config the configuration to install
     * @return the installed configuration, enabling callers to tweak it before invoking the service
     */
    private MutableAssaultConfig setMutableConfig(MutableAssaultConfig config) {
        try {
            Field field = AssaultEngine.class.getDeclaredField("mutableConfig");
            field.setAccessible(true);
            field.set(engine, config);
            return config;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inject mutable config into engine", e);
        }
    }
}
