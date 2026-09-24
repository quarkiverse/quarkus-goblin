package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;

import jakarta.ws.rs.client.ClientRequestContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GoblinChaosClientFilter}, exercising the filter against a fake JAX-RS client request
 * context so the assault decision and history recording can be asserted without a real REST client.
 */
class GoblinChaosClientFilterTest {

    private static final URI TARGET = URI.create("http://localhost:8081/api/hello");

    private final AssaultEngine engine = new AssaultEngine();
    private final GoblinChaosClientFilter filter = new GoblinChaosClientFilter();
    private MutableAssaultConfig config;

    @BeforeEach
    void setUp() throws Exception {
        engine.setActive(true);
        config = new MutableAssaultConfig();
        setMutableConfig(config);
        filter.engine = engine;
    }

    @Test
    void clientLatencyAppliesDelayAndRecords() throws Exception {
        config.setClientLatencyEnabled(true);
        config.setLatencyRange(5, 10);

        long start = System.nanoTime();
        filter.filter(requestContext("GET", TARGET));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getFirst();
        assertEquals("latency", record.type());
        assertEquals("REST-Client GET http://localhost:8081/api/hello", record.method());
        assertTrue(elapsedMs >= 5, "expected at least 5ms delay, got " + elapsedMs + "ms");
    }

    @Test
    void noAssaultWhenClientAssaultsDisabled() throws Exception {
        filter.filter(requestContext("GET", TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void clientExceptionThrowsConfiguredExceptionAndRecords() {
        config.setClientExceptionEnabled(true);
        config.setExceptionType("java.lang.IllegalStateException");
        config.setExceptionMessage("client boom");

        assertThrows(IllegalStateException.class,
                () -> filter.filter(requestContext("GET", TARGET)));

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getFirst();
        assertEquals("exception", record.type());
        assertEquals("REST-Client GET http://localhost:8081/api/hello", record.method());
    }

    @Test
    void latencyThenExceptionBothApply() {
        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);
        config.setExceptionType("java.lang.RuntimeException");
        config.setLatencyRange(5, 10);

        long start = System.nanoTime();
        assertThrows(RuntimeException.class, () -> filter.filter(requestContext("GET", TARGET)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, engine.getHistory().size());
        assertEquals("latency", engine.getHistory().getFirst().type());
        assertEquals("exception", engine.getHistory().get(1).type());
        assertTrue(elapsedMs >= 5, "expected delay before the exception, got " + elapsedMs + "ms");
    }

    @Test
    void inactiveEngineSkipsAssault() throws Exception {
        config.setClientLatencyEnabled(true);
        engine.setActive(false);

        filter.filter(requestContext("GET", TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void zeroTargetLevelSkipsAssault() throws Exception {
        config.setClientLatencyEnabled(true);
        config.setTargetLevel(0);

        filter.filter(requestContext("GET", TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    private void setMutableConfig(MutableAssaultConfig configValue) {
        try {
            Field field = AssaultEngine.class.getDeclaredField("mutableConfig");
            field.setAccessible(true);
            field.set(engine, configValue);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inject mutable config into engine", e);
        }
    }

    /**
     * Builds a fake {@link ClientRequestContext} that only honours {@code getMethod()} and {@code getUri()} and returns
     * inert defaults for the rest of the interface.
     *
     * @param method the HTTP method reported by the context
     * @param uri the request URI reported by the context
     * @return a dynamically generated stub, never {@code null}
     */
    private static ClientRequestContext requestContext(String method, URI uri) {
        InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
            case "getMethod" -> method;
            case "getUri" -> uri;
            case "toString" -> "fake ClientRequestContext " + method + " " + uri;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(m.getReturnType());
        };
        return (ClientRequestContext) Proxy.newProxyInstance(
                ClientRequestContext.class.getClassLoader(),
                new Class<?>[] { ClientRequestContext.class },
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    @Test
    void historyIdentifierDropsQueryUserInfoAndFragment() {
        assertEquals("https://api.example.com:8443/v1/items",
                GoblinChaosClientFilter.sanitize(
                        java.net.URI.create("https://user:secret@api.example.com:8443/v1/items?token=abc#frag")));
        assertEquals("http://localhost/x", GoblinChaosClientFilter.sanitize(java.net.URI.create("http://localhost/x")));
        assertEquals("<unknown>", GoblinChaosClientFilter.sanitize(null));
    }
}
