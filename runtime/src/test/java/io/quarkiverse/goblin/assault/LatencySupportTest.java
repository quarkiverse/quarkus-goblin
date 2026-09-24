package io.quarkiverse.goblin.assault;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.MutableAssaultConfig;

class LatencySupportTest {

    @Test
    void drawStaysWithinTheInclusiveRange() {
        for (int i = 0; i < 1000; i++) {
            long delay = LatencySupport.drawDelay(5, 10);
            assertTrue(delay >= 5 && delay <= 10, "out of range: " + delay);
        }
    }

    @Test
    void drawToleratesAReversedRange() {
        for (int i = 0; i < 100; i++) {
            long delay = LatencySupport.drawDelay(10, 5);
            assertTrue(delay >= 5 && delay <= 10, "out of range: " + delay);
        }
    }

    @Test
    void drawReturnsTheBoundWhenMinEqualsMax() {
        assertEquals(42, LatencySupport.drawDelay(42, 42));
    }

    @Test
    void drawNeverReturnsANegativeDelay() {
        for (int i = 0; i < 100; i++) {
            assertTrue(LatencySupport.drawDelay(-50, -10) >= 0);
            assertTrue(LatencySupport.drawDelay(-50, 3) >= 0);
        }
    }

    @Test
    void drawNeverOverflowsOnLongMaxValue() {
        assertDoesNotThrow(() -> LatencySupport.drawDelay(Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertDoesNotThrow(() -> LatencySupport.drawDelay(0, Long.MAX_VALUE));
    }

    @Test
    void drawFromConfigUsesTheConfiguredRange() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyRange(7, 7);
        assertEquals(7, LatencySupport.drawDelay(config));
    }

    @Test
    void blockingIsAllowedOutsideAQuarkusRuntime() {
        assertTrue(LatencySupport.isBlockingAllowed());
    }

    @Test
    void sleepAppliesTheDelay() throws InterruptedException {
        long start = System.nanoTime();
        assertTrue(LatencySupport.sleep(5, "target"));
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 5);
    }

    @Test
    void sleepPropagatesInterruptionAndRestoresTheFlag() {
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> LatencySupport.sleep(1000, "target"));
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored (and is cleared here)");
    }
}
