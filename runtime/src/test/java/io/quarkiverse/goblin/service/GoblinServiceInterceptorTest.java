package io.quarkiverse.goblin.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import jakarta.interceptor.InvocationContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;

class GoblinServiceInterceptorTest {

    /** Engine double: fixed configuration, scripted level-gate draws and an in-memory record list. */
    static final class FakeEngine extends AssaultEngine {
        final MutableAssaultConfig config = new MutableAssaultConfig();
        final List<String> records = new ArrayList<>();
        final List<Long> latencies = new ArrayList<>();
        boolean active = true;
        boolean redraw = true;
        int redraws;

        @Override
        public MutableAssaultConfig getMutableConfig() {
            return config;
        }

        @Override
        public MutableAssaultConfig configSnapshot() {
            return config.snapshot();
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public boolean drawLevelGate() {
            redraws++;
            return redraw;
        }

        @Override
        public void recordAssault(AssaultSource source, String method, String type, long latencyMs) {
            assertEquals(AssaultSource.SERVICE, source);
            records.add(type);
            latencies.add(latencyMs);
        }
    }

    /** Minimal invocation context running a callable as the intercepted method body. */
    static final class FakeInvocation implements InvocationContext {
        private final Callable<Object> body;
        int proceeded;

        FakeInvocation(Callable<Object> body) {
            this.body = body;
        }

        @Override
        public Object proceed() throws Exception {
            proceeded++;
            return body.call();
        }

        @Override
        public Method getMethod() {
            try {
                return GoblinServiceInterceptorTest.class.getDeclaredMethod("target");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object getTarget() {
            return null;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(Object[] params) {
        }

        @Override
        public Map<String, Object> getContextData() {
            return new HashMap<>();
        }
    }

    static void target() {
    }

    private final FakeEngine engine = new FakeEngine();
    private final GoblinServiceInterceptor interceptor = new GoblinServiceInterceptor();

    @BeforeEach
    void setUp() {
        interceptor.engine = engine;
        engine.config.setLatencyEnabled(false);
        engine.config.setExceptionEnabled(false);
        engine.config.setLatencyRange(0, 0);
    }

    @AfterEach
    void cleanup() {
        ChaosRequestContext.clear();
    }

    @Test
    void passesThroughWhenServiceIsNotArmed() throws Exception {
        engine.config.setExceptionEnabled(true);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.HTTP_IN);

        assertEquals("ok", interceptor.aroundInvoke(new FakeInvocation(() -> "ok")));
        assertTrue(engine.records.isEmpty());
    }

    @Test
    void passesThroughWhenTheEngineIsInactive() throws Exception {
        engine.config.setExceptionEnabled(true);
        engine.active = false;
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);

        assertEquals("ok", interceptor.aroundInvoke(new FakeInvocation(() -> "ok")));
        assertTrue(engine.records.isEmpty());
    }

    @Test
    void latencyThenExceptionRecordsBothAndNeverRunsTheBody() {
        engine.config.setLatencyEnabled(true);
        engine.config.setExceptionEnabled(true);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        FakeInvocation invocation = new FakeInvocation(() -> "ok");

        assertThrows(RuntimeException.class, () -> interceptor.aroundInvoke(invocation));
        assertEquals(List.of("latency", "exception"), engine.records);
        assertEquals(0, invocation.proceeded);
    }

    @Test
    void nestedInterceptedCallsAreNotAssaultedAgain() throws Exception {
        engine.config.setLatencyEnabled(true);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        FakeInvocation inner = new FakeInvocation(() -> "inner");
        FakeInvocation outer = new FakeInvocation(() -> interceptor.aroundInvoke(inner));

        assertEquals("inner", interceptor.aroundInvoke(outer));
        assertEquals(List.of("latency"), engine.records, "only the outermost call is assaulted");
        assertEquals(0, engine.redraws);
    }

    @Test
    void furtherOutermostCallsRedrawTheLevelGate() {
        engine.config.setExceptionEnabled(true);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);

        assertThrows(RuntimeException.class, () -> interceptor.aroundInvoke(new FakeInvocation(() -> "ok")));
        assertEquals(0, engine.redraws, "the first call uses the per-request decision");

        engine.redraw = false;
        assertDoesNotThrow(() -> interceptor.aroundInvoke(new FakeInvocation(() -> "ok")),
                "a retry whose draw fails must reach the bean");
        assertEquals(1, engine.redraws);

        engine.redraw = true;
        assertThrows(RuntimeException.class, () -> interceptor.aroundInvoke(new FakeInvocation(() -> "ok")));
        assertEquals(List.of("exception", "exception"), engine.records);
    }

    @Test
    void interruptedLatencyIsRecordedAndPropagated() {
        engine.config.setLatencyEnabled(true);
        engine.config.setLatencyRange(1000, 1000);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);
        FakeInvocation invocation = new FakeInvocation(() -> "ok");

        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> interceptor.aroundInvoke(invocation));
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
        assertEquals(List.of("latency"), engine.records);
        assertTrue(engine.latencies.get(0) < 1000, "the endured delay is recorded, not the drawn 1000 ms");
        assertEquals(0, invocation.proceeded);
    }

    @Test
    void depthIsReleasedWhenTheBodyThrows() {
        engine.config.setLatencyEnabled(true);
        ChaosRequestContext.setAssaultLayer(ChaosLayer.SERVICE);

        assertThrows(IllegalStateException.class, () -> interceptor.aroundInvoke(new FakeInvocation(() -> {
            throw new IllegalStateException("boom");
        })));
        assertTrue(ChaosRequestContext.enterService(), "the nesting depth must be back to zero");
    }
}
