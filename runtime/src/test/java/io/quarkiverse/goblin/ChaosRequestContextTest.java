package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChaosRequestContextTest {

    @AfterEach
    void cleanup() {
        ChaosRequestContext.clear();
    }

    @Test
    void setIsAndClear() {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        assertTrue(ChaosRequestContext.isServiceArmed());
        assertTrue(ChaosRequestContext.is(ChaosLayer.SERVICE));
        assertEquals(ChaosLayer.SERVICE, ChaosRequestContext.assaultLayer());

        ChaosRequestContext.clear();
        assertFalse(ChaosRequestContext.isServiceArmed());
        assertNull(ChaosRequestContext.assaultLayer());
    }

    @Test
    void nullLayerUnbindsTheThread() {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        ChaosRequestContext.setAssaultLayer(null);
        assertNull(ChaosRequestContext.assaultLayer());
        assertFalse(ChaosRequestContext.enterService(), "no armed request: never the outermost assaulted call");
    }

    @Test
    void onlyTheOutermostServiceCallIsReported() {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);

        assertTrue(ChaosRequestContext.enterService());
        assertFalse(ChaosRequestContext.enterService(), "a nested call is not the outermost one");
        ChaosRequestContext.exitService();
        ChaosRequestContext.exitService();

        assertTrue(ChaosRequestContext.enterService(), "a sequential call (e.g. a retry) is outermost again");
        ChaosRequestContext.exitService();
    }

    @Test
    void markFiredReportsEarlierFires() {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        assertFalse(ChaosRequestContext.markFired());
        assertTrue(ChaosRequestContext.markFired());
    }

    @Test
    void newDecisionResetsTheServiceState() {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        ChaosRequestContext.enterService();
        ChaosRequestContext.markFired();

        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        assertFalse(ChaosRequestContext.markFired());
        assertTrue(ChaosRequestContext.enterService());
    }

    @Test
    void stateIsNotSharedAcrossThreads() throws InterruptedException {
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        boolean[] seen = new boolean[1];
        Thread other = new Thread(() -> seen[0] = ChaosRequestContext.isServiceArmed());
        other.start();
        other.join();
        assertFalse(seen[0]);
    }
}
