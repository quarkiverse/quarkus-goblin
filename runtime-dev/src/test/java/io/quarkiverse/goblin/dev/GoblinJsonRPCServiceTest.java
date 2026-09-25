package io.quarkiverse.goblin.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultProfile;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
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
            "clientLatencyEnabled", "clientExceptionEnabled", "responseBodyEnabled", "responseHeaderEnabled",
    };

    @Test
    void autoOffIsScheduledReportedAndCancelledThroughJsonRpc() {
        try {
            assertEquals(0L, service.getStatus().getLong("autoOffRemainingMs"));

            JsonObject started = service.startAutoOff(5);
            assertTrue(started.getBoolean("ok"));
            long remaining = service.getStatus().getLong("autoOffRemainingMs");
            assertTrue(remaining > 0 && remaining <= 5 * 60_000L, "remaining " + remaining);

            JsonObject cancelled = service.cancelAutoOff();
            assertTrue(cancelled.getBoolean("ok"));
            assertEquals(0L, service.getStatus().getLong("autoOffRemainingMs"));

            assertFalse(service.startAutoOff(0).getBoolean("ok"), "a non-positive delay is rejected");
        } finally {
            engine.cancelAutoOff();
        }
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
     * The layers backed by an installed hook are exposed so the Dev UI can disable the others; without a datasource or
     * messaging only the built-in layers are offered.
     */
    @Test
    void availableLayersExposedInConfigAndStatus() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        List<Object> expected = List.of("SERVICE", "HTTP_OUT", "HTTP_IN");
        assertEquals(expected, service.getConfig().getJsonArray("availableLayers").getList());
        assertEquals(expected, service.getStatus().getJsonArray("availableLayers").getList());
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
     * The config and status payloads must expose the response header assault toggle and rules.
     */
    @Test
    void responseHeaderExposedInConfigAndStatus() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = service.getConfig();
        assertTrue(config.containsKey("responseHeaderEnabled"));
        JsonObject headers = config.getJsonObject("headers");
        assertNotNull(headers);

        JsonObject status = service.getStatus();
        assertTrue(status.containsKey("responseHeaderEnabled"));
    }

    /**
     * {@code toggleResponseHeader} flips the response header toggle and reports the new value.
     */
    @Test
    void toggleResponseHeaderFlipsValue() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.toggleResponseHeader();

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseHeaderEnabled"));

        JsonObject again = service.toggleResponseHeader();
        assertFalse(again.getBoolean("responseHeaderEnabled"));
    }

    /**
     * {@code setResponseHeaderInfo} stores the rule, normalises the action to upper case, and reports it back.
     */
    @Test
    void setResponseHeaderInfoStoresRule() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("X-Goblin", "set", "chaos");

        assertTrue(result.getBoolean("ok"));
        assertEquals("SET", result.getString("action"));
        assertEquals("chaos", result.getString("value"));
        MutableAssaultConfig.HeaderRule rule = service.engine.getMutableConfig().getResponseHeaders().get("X-Goblin");
        assertNotNull(rule);
        assertEquals(ResponseHeaderAction.SET, rule.action());
    }

    /**
     * {@code setResponseHeaderInfo} trims the header name and exposes the reported value from the effective rule.
     */
    @Test
    void setResponseHeaderInfoTrimsName() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("  X-Goblin  ", "set", "chaos");

        assertTrue(result.getBoolean("ok"));
        assertEquals("X-Goblin", result.getString("name"));
    }

    /**
     * {@code setResponseHeaderInfo} rejects an unknown action with a stable error object.
     */
    @Test
    void setResponseHeaderInfoRejectsUnknownAction() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("X-Goblin", "SHRINK", "chaos");

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    /**
     * {@code setResponseHeaderInfo} rejects a blank header name with a stable error object.
     */
    @Test
    void setResponseHeaderInfoRejectsBlankName() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("  ", "SET", "chaos");

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
    }

    /**
     * {@code setResponseHeaderInfo} rejects a value carrying CR or LF instead of storing a header that could split the
     * response.
     */
    @Test
    void setResponseHeaderInfoRejectsControlCharacters() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("X-Goblin", "SET", "chaos\r\nInjected: true");

        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("error").contains("CR, LF"), "unexpected error: " + result.getString("error"));
        assertTrue(service.engine.getMutableConfig().getResponseHeaders().isEmpty());
    }

    /**
     * {@code removeResponseHeader} drops the stored rule and reports the remaining configuration.
     */
    @Test
    void removeResponseHeaderDropsRule() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        service.setResponseHeaderInfo("X-Goblin", "SET", "chaos");
        JsonObject result = service.removeResponseHeader("X-Goblin");

        assertTrue(result.getBoolean("ok"));
        assertTrue(service.engine.getMutableConfig().getResponseHeaders().isEmpty());
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

        JsonObject result = service.applyConfig(partial.getMap());

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

        JsonObject result = service.applyConfig(config.getMap());

        assertTrue(result.getBoolean("ok"));
        assertEquals("SLOW_FAILURE", result.getString("profile"));
        assertTrue(result.getBoolean("exceptionEnabled"), "profile defaults apply");
        assertFalse(result.getBoolean("latencyEnabled"), "explicit field overrides the profile default");
    }

    /**
     * {@code applyConfig} replaces the configured header rules with those declared in the payload.
     */
    @Test
    void applyConfigReplacesHeaderRules() throws Exception {
        setMutableConfig(new MutableAssaultConfig());
        service.setResponseHeaderInfo("X-Goblin", "SET", "boo");

        JsonObject config = new JsonObject()
                .put("responseHeaderEnabled", true)
                .put("headers", new JsonObject()
                        .put("X-Goblin", new JsonObject().put("action", "SET").put("value", "chaos"))
                        .put("Server", new JsonObject().put("action", "REMOVE").put("value", "")));

        JsonObject result = service.applyConfig(config.getMap());

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseHeaderEnabled"));
        JsonObject headers = result.getJsonObject("headers");
        assertEquals("chaos", headers.getJsonObject("X-Goblin").getString("value"));
        assertEquals(2, headers.size());
        assertTrue(service.engine.getMutableConfig().getResponseHeaders().containsKey("Server"));
    }

    /**
     * {@code applyConfig} warns (but is {@code ok=true}) when a header rule carries an unknown action and leaves the
     * remaining rules applied.
     */
    @Test
    void applyConfigSkipsHeaderRuleWithUnknownAction() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = new JsonObject()
                .put("headers", new JsonObject()
                        .put("X-Goblin", new JsonObject().put("action", "BOGUS").put("value", "chaos"))
                        .put("Server", new JsonObject().put("action", "SET").put("value", "goblin")));

        JsonObject result = service.applyConfig(config.getMap());

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getString("warning").contains("BOGUS"));
        assertFalse(service.engine.getMutableConfig().getResponseHeaders().containsKey("X-Goblin"));
        assertTrue(service.engine.getMutableConfig().getResponseHeaders().containsKey("Server"));
    }

    /**
     * {@code applyConfig} skips a header rule whose value carries CR or LF and keeps the remaining rules applied.
     */
    @Test
    void applyConfigSkipsHeaderRuleWithUnsafeValue() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject config = new JsonObject()
                .put("headers", new JsonObject()
                        .put("X-Goblin", new JsonObject().put("action", "SET").put("value", "chaos\nInjected: true"))
                        .put("Server", new JsonObject().put("action", "SET").put("value", "goblin")));

        JsonObject result = service.applyConfig(config.getMap());

        assertTrue(result.getBoolean("ok"));
        assertNotNull(result.getString("warning"));
        assertFalse(service.engine.getMutableConfig().getResponseHeaders().containsKey("X-Goblin"));
        assertTrue(service.engine.getMutableConfig().getResponseHeaders().containsKey("Server"));
    }

    /**
     * Reproduces the JSON-RPC transport deserialization: the Dev UI codec hands the service a plain map whose nested
     * JSON objects are ordinary {@link java.util.Map} instances. Nested header rules must survive the conversion.
     */
    @Test
    void applyConfigAcceptsTransportDeserializedPayloadWithNestedHeaders() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        String payload = """
                {
                  "responseHeaderEnabled": true,
                  "headers": {
                    "X-Goblin": { "action": "SET", "value": "chaos" },
                    "Server": { "action": "REMOVE", "value": "" }
                  }
                }
                """;
        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = Json.decodeValue(payload, Map.class);

        JsonObject result = service.applyConfig(decoded);

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("responseHeaderEnabled"));
        assertEquals("chaos",
                service.engine.getMutableConfig().getResponseHeaders().get("X-Goblin").value());
        assertEquals(ResponseHeaderAction.REMOVE,
                service.engine.getMutableConfig().getResponseHeaders().get("Server").action());
    }

    /**
     * {@code applyConfig} rejects a missing payload instead of failing with a {@code NullPointerException} (the Dev UI
     * JSON-RPC router passes {@code null} when the incoming params object carries no {@code config} key).
     */
    @Test
    void applyConfigRejectsMissingPayload() throws Exception {
        setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.applyConfig(null);

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
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
     * The counters endpoint also breaks the assaults down by source, so client-side assaults (recorded as plain
     * {@code latency} / {@code exception} types) are never counted as server-side ones.
     */
    @Test
    void getCountersReportsPerSource() throws Exception {
        setMutableConfig(new MutableAssaultConfig());
        engine.recordAssault("SampleResource.hello", "latency");
        engine.recordAssault(AssaultSource.REST_CLIENT, "REST-Client GET http://x", "latency", 5);
        engine.recordAssault(AssaultSource.DATABASE, "Database <default> connection", "exception");

        JsonObject bySource = service.getCounters().getJsonObject("bySource");

        assertEquals(1L, bySource.getLong("server"));
        assertEquals(1L, bySource.getLong("rest-client"));
        assertEquals(1L, bySource.getLong("database"));

        service.resetCounters();
        assertTrue(service.getCounters().getJsonObject("bySource").isEmpty());
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
    /**
     * An import that fails half-way (unknown profile here) must leave the configuration untouched.
     */
    @Test
    void applyConfigRejectedPayloadChangesNothing() throws Exception {
        MutableAssaultConfig cfg = setMutableConfig(new MutableAssaultConfig());
        cfg.setTargetLevel(30);

        JsonObject result = service.applyConfig(new JsonObject()
                .put("level", 80)
                .put("profile", "CHAOS")
                .put("latencyEnabled", false).getMap());

        assertFalse(result.getBoolean("ok"));
        assertNotNull(result.getString("error"));
        assertEquals(30, cfg.getTargetLevel(), "nothing may be applied from a rejected payload");
        assertTrue(cfg.isLatencyEnabled());
    }

    /**
     * Booleans and numbers given as strings (a hand-edited export) are converted; values that cannot be converted
     * are skipped with a warning while the rest of the payload is applied.
     */
    @Test
    void applyConfigToleratesStringValuesAndSkipsInvalidOnes() throws Exception {
        MutableAssaultConfig cfg = setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.applyConfig(new JsonObject()
                .put("exceptionEnabled", "true")
                .put("level", "50")
                .put("latencyEnabled", 42)
                .put("httpStatus", new JsonObject().put("code", "not-a-code")).getMap());

        assertTrue(result.getBoolean("ok"));
        assertTrue(cfg.isExceptionEnabled());
        assertEquals(50, cfg.getTargetLevel());
        assertTrue(cfg.isLatencyEnabled(), "an unconvertible value is skipped, the field keeps its value");
        assertEquals(503, cfg.getHttpStatusCode());
        String warning = result.getString("warning");
        assertTrue(warning.contains("latencyEnabled") && warning.contains("code"), warning);
    }

    /**
     * An invalid header name is skipped with a warning; the other rules of the payload are still stored.
     */
    @Test
    void applyConfigSkipsInvalidHeaderNamesWithoutLosingTheOtherRules() throws Exception {
        MutableAssaultConfig cfg = setMutableConfig(new MutableAssaultConfig());
        cfg.setResponseHeader("X-Existing", ResponseHeaderAction.SET, "kept");

        JsonObject result = service.applyConfig(new JsonObject().put("headers", new JsonObject()
                .put("X-Existing", new JsonObject().put("action", "SET").put("value", "kept"))
                .put("X Bad", new JsonObject().put("action", "SET").put("value", "v"))).getMap());

        assertTrue(result.getBoolean("ok"));
        assertEquals("kept", cfg.getResponseHeaders().get("X-Existing").value());
        assertFalse(cfg.getResponseHeaders().containsKey("X Bad"));
        assertTrue(result.getString("warning").contains("X Bad"), result.getString("warning"));
    }

    /**
     * A whole import is persisted once, not once per field.
     */
    @Test
    void applyConfigPublishesTheImportAsASingleChange() throws Exception {
        MutableAssaultConfig cfg = setMutableConfig(new MutableAssaultConfig());
        int[] changes = { 0 };
        cfg.setOnChange(() -> changes[0]++);

        service.applyConfig(new JsonObject()
                .put("exceptionEnabled", true)
                .put("level", 42)
                .put("layers", new JsonArray().add("SERVICE")).getMap());

        assertEquals(1, changes[0]);
    }

    /**
     * {@code setResponseHeaderInfo} reports an invalid header name as {@code ok=false} instead of failing the call.
     */
    @Test
    void setResponseHeaderInfoRejectsInvalidNames() throws Exception {
        MutableAssaultConfig cfg = setMutableConfig(new MutableAssaultConfig());

        JsonObject result = service.setResponseHeaderInfo("X Bad", "SET", "v");

        assertFalse(result.getBoolean("ok"));
        assertTrue(cfg.getResponseHeaders().isEmpty());
    }

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
