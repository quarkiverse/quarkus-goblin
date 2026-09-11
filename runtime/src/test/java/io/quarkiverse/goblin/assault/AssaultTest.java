package io.quarkiverse.goblin.assault;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;

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

    /**
     * Verifies that the exception assault falls back to a {@link RuntimeException} carrying the configured message
     * when the configured class cannot be loaded.
     */
    @Test
    void exceptionFallsBackToRuntimeExceptionForUnknownClass() {
        config.setExceptionEnabled(true);
        config.setExceptionType("com.example.DoesNotExist");
        config.setExceptionMessage("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new ExceptionAssault().apply(context("TestResource.hello")));
        assertEquals("boom", thrown.getMessage());
    }

    /**
     * Verifies the failure reason reported for an unknown exception class.
     */
    @Test
    void failureReasonForUnknownClass() {
        String reason = ExceptionAssault.failureReason("com.example.DoesNotExist", new ClassNotFoundException());
        assertEquals("class 'com.example.DoesNotExist' not found", reason);
    }

    /**
     * Verifies the failure reason reported when the class has no public {@code String} constructor.
     */
    @Test
    void failureReasonForMissingStringConstructor() {
        String reason = ExceptionAssault.failureReason("java.lang.Class", new NoSuchMethodException());
        assertEquals("class 'java.lang.Class' has no String constructor", reason);
    }

    /**
     * Verifies the failure reason reported when the class does not extend {@link RuntimeException}.
     */
    @Test
    void failureReasonForNonRuntimeExceptionType() {
        String reason = ExceptionAssault.failureReason("java.lang.Error", new ClassCastException());
        assertEquals("class 'java.lang.Error' does not extend RuntimeException", reason);
    }

    /**
     * Verifies the failure reason reported when the {@code String} constructor throws during instantiation.
     */
    @Test
    void failureReasonForConstructorFailure() {
        Exception cause = new InvocationTargetException(new IllegalStateException("ctor boom"));
        String reason = ExceptionAssault.failureReason("some.Type", cause);
        assertEquals("constructor threw java.lang.IllegalStateException", reason);
    }

    @Test
    void exceptionDisabledWhenConfigSaysSo() {
        config.setExceptionEnabled(false);
        assertFalse(new ExceptionAssault().isEnabled(config));
    }
}