package io.quarkiverse.goblin.assault;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;

class AssaultTest {

    private final AssaultEngine engine = new AssaultEngine();
    private final MutableAssaultConfig config = new MutableAssaultConfig();

    private AssaultContext context(String method) {
        return new AssaultContext(null, config, engine, method);
    }

    @Test
    void latencyAppliesDelayAndRecords() {
        config.setLatencyEnabled(true);
        config.setLatencyRange(5, 10);

        long start = System.nanoTime();
        AssaultOutcome outcome = new LatencyAssault().apply(context("TestResource.hello"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertSame(AssaultOutcome.CONTINUE, outcome);
        assertEquals(1, engine.getHistory().size());
        assertEquals("latency", engine.getHistory().get(0).type());
        assertEquals("TestResource.hello", engine.getHistory().get(0).method());
        assertTrue(elapsedMs >= 5, "expected at least 5ms delay, got " + elapsedMs);
    }

    @Test
    void latencyDisabledWhenConfigSaysSo() {
        config.setLatencyEnabled(false);
        assertFalse(new LatencyAssault().isEnabled(config));
    }

    @Test
    void exceptionThrowsConfiguredExceptionAndRecords() {
        config.setExceptionEnabled(true);
        config.setExceptionType("java.lang.IllegalStateException");
        config.setExceptionMessage("boom");

        assertThrows(IllegalStateException.class, () -> new ExceptionAssault().apply(context("TestResource.hello")));
        assertEquals(1, engine.getHistory().size());
        assertEquals("exception", engine.getHistory().get(0).type());
    }

    @Test
    void exceptionFallsBackToRuntimeExceptionForUnknownClass() {
        config.setExceptionEnabled(true);
        config.setExceptionType("com.example.DoesNotExist");
        config.setExceptionMessage("boom");

        assertThrows(RuntimeException.class, () -> new ExceptionAssault().apply(context("TestResource.hello")));
    }

    @Test
    void exceptionDisabledWhenConfigSaysSo() {
        config.setExceptionEnabled(false);
        assertFalse(new ExceptionAssault().isEnabled(config));
    }
}