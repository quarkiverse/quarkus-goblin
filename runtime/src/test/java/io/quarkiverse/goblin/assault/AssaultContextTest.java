package io.quarkiverse.goblin.assault;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;
import io.quarkiverse.goblin.internal.MutableAssaultConfigImpl;

class AssaultContextTest {

    private static final Assault CUSTOM = new Assault() {
        @Override
        public AssaultType type() {
            return AssaultType.LATENCY;
        }

        @Override
        public boolean isEnabled(AssaultConfig config) {
            return true;
        }

        @Override
        public String recordLabel() {
            return "custom-latency";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public AssaultOutcome apply(AssaultContext context) {
            return AssaultOutcome.CONTINUE;
        }
    };

    @Test
    void recordGoesToTheGoblinEngineWithTheTypeAndLabelOfTheAssault() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        AssaultContext context = new AssaultContext(null, new MutableAssaultConfigImpl(), engine, "com.acme.Api.call");

        context.record(CUSTOM, 42);

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().get(0);
        assertEquals("com.acme.Api.call", record.method());
        assertEquals("custom-latency", record.type());
        assertEquals(AssaultType.LATENCY, record.assaultType());
        assertEquals(AssaultSource.SERVER, record.source());
        assertEquals(42, record.latencyMs());
    }

    @Test
    void recordIsANoOpWithAnEngineGoblinDidNotProvide() {
        AtomicInteger calls = new AtomicInteger();
        AssaultEngine stub = (AssaultEngine) Proxy.newProxyInstance(AssaultEngine.class.getClassLoader(),
                new Class<?>[] { AssaultEngine.class }, (proxy, method, args) -> {
                    calls.incrementAndGet();
                    return null;
                });
        AssaultContext context = new AssaultContext(null, new MutableAssaultConfigImpl(), stub, "com.acme.Api.call");

        assertDoesNotThrow(() -> context.record(CUSTOM));
        assertEquals(0, calls.get());
    }
}
