package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MutableAssaultConfigTest {

    @Test
    void onChangeListenerIsCalledOnSetter() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        AtomicInteger callCount = new AtomicInteger(0);
        config.setOnChange(callCount::incrementAndGet);

        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);
        config.setHttpStatusEnabled(true);
        config.setDependencyDegradationEnabled(true);
        config.setLatencyMinMs(200);
        config.setLatencyMaxMs(800);
        config.setExceptionType("java.io.IOException");
        config.setExceptionMessage("boom");
        config.setHttpStatusCode(418);
        config.setHttpStatusMessage("I'm a teapot");
        config.setTargetLevel(50);

        assertEquals(11, callCount.get());
    }

    @Test
    void onChangeListenerNotCalledWhenNotSet() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> {
            config.setLatencyEnabled(false);
            config.setTargetLevel(42);
        });
    }

    @Test
    void onChangeListenerCanBeReplaced() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        AtomicInteger first = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);

        config.setOnChange(first::incrementAndGet);
        config.setLatencyEnabled(false);
        assertEquals(1, first.get());
        assertEquals(0, second.get());

        config.setOnChange(second::incrementAndGet);
        config.setLatencyEnabled(true);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void fromConfigLoadsCorrectValues() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertTrue(config.isLatencyEnabled());
        assertFalse(config.isExceptionEnabled());
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(5000, config.getLatencyMaxMs());
        assertEquals(100, config.getTargetLevel());
    }

    @Test
    void targetLevelIsClamped() {
        MutableAssaultConfig config = new MutableAssaultConfig();

        config.setTargetLevel(150);
        assertEquals(100, config.getTargetLevel());

        config.setTargetLevel(-10);
        assertEquals(0, config.getTargetLevel());
    }

    @Test
    void describeAssaultsReflectsState() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        assertEquals("no assault enabled", config.describeAssaults());

        config.setLatencyEnabled(true);
        config.setLatencyMinMs(100);
        config.setLatencyMaxMs(500);
        assertTrue(config.describeAssaults().contains("latency"));
        assertTrue(config.describeAssaults().contains("100 - 500 ms"));
    }

    @Test
    void hasAnyAssaultEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        assertFalse(config.hasAnyAssaultEnabled());

        config.setExceptionEnabled(true);
        assertTrue(config.hasAnyAssaultEnabled());
    }
}
