package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultConfigChange;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.dev.GoblinJsonRPCService;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #71: in test mode, a CDI {@code AssaultObserver} sees every configuration change made through the JSON-RPC
 * service (the Dev UI and Dev MCP surface) exactly once, with the configuration before and after it -- so a test can
 * assert on the exact configuration an assault ran with.
 */
@QuarkusTest
class GoblinConfigChangeIntegrationTest {

    @Inject
    GoblinJsonRPCService jsonRpc;

    @Inject
    AssaultEngine engine;

    @Inject
    RecordingConfigObserver recorder;

    @BeforeEach
    void resetState() {
        engine.getMutableConfig().resetToDefaults();
        recorder.clear();
    }

    @Test
    void aSingleToolCallIsOneChange() {
        jsonRpc.setTargetLevel(35);

        List<AssaultConfigChange> changes = recorder.changes();
        assertEquals(1, changes.size(), "got: " + describe(changes));
        assertEquals(100, changes.getFirst().previous().getTargetLevel());
        assertEquals(35, changes.getFirst().current().getTargetLevel());
    }

    @Test
    void applyConfigIsOneChangeWhateverTheNumberOfKeys() {
        jsonRpc.applyConfig(Map.of(
                "latencyEnabled", false,
                "exceptionEnabled", true,
                "level", 40,
                "layers", List.of("SERVICE"),
                "latency", Map.of("minMilliseconds", 300, "maxMilliseconds", 500)));

        List<AssaultConfigChange> changes = recorder.changes();
        assertEquals(1, changes.size(), "a multi-key applyConfig is published at once, got: " + describe(changes));
        AssaultConfigChange change = changes.getFirst();
        assertTrue(change.previous().isLatencyEnabled());
        assertFalse(change.current().isLatencyEnabled());
        assertTrue(change.current().isExceptionEnabled());
        assertEquals(40, change.current().getTargetLevel());
        assertEquals(300, change.current().getLatencyMinMs());
        assertEquals("exception enabled (java.lang.RuntimeException: \"Goblin chaos: simulated exception\")",
                change.currentDescription());
    }

    @Test
    void activatingChaosIsNotAConfigurationChange() {
        jsonRpc.setActive(false);
        jsonRpc.setActive(true);

        assertTrue(recorder.changes().isEmpty(),
                "activation is reported by onActiveChange, got: " + describe(recorder.changes()));
    }

    /** Renders the changes readably in an assertion message: MutableAssaultConfig has no toString(). */
    private static String describe(List<AssaultConfigChange> changes) {
        return changes.stream()
                .map(change -> "[" + change.previousDescription() + " -> " + change.currentDescription() + "]")
                .toList()
                .toString();
    }
}
