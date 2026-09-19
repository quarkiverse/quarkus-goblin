package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResponseHeaderTransformer}, verifying the set/remove semantics and the history recording on a
 * fake JAX-RS response context backed by a real {@link AssaultEngine}.
 */
class ResponseHeaderTransformerTest {

    private final AssaultEngine engine = new AssaultEngine();

    @Test
    void setForcesHeaderOnEvenWhenAbsent() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("chaos", headers.getFirst("X-Goblin"));
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-header-set:X-Goblin", engine.getHistory().get(0).type());
    }

    @Test
    void setReplacesAnExistingValue() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "new");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Goblin", "old");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("new", headers.getFirst("X-Goblin"));
    }

    @Test
    void removeDeletesAnExistingHeader() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Goblin", "chaos");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertNull(headers.getFirst("X-Goblin"));
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-header-remove:X-Goblin", engine.getHistory().get(0).type());
    }

    @Test
    void removeLeavesAbsentHeaderUnrecorded() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void everyConfiguredRuleIsAppliedAndRecorded() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Added", ResponseHeaderAction.SET, "a");
        config.setResponseHeader("Server", ResponseHeaderAction.SET, "goblin");
        config.setResponseHeader("Content-Type", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("Server", "quarkus");
        headers.putSingle("Content-Type", "application/json");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("a", headers.getFirst("X-Added"));
        assertEquals("goblin", headers.getFirst("Server"));
        assertNull(headers.getFirst("Content-Type"));
        assertEquals(3, engine.getHistory().size());
    }

    @Test
    void emptyConfigurationRecordsNothing() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), new MutableAssaultConfig(), engine, "Api.hello");

        assertTrue(headers.isEmpty());
        assertTrue(engine.getHistory().isEmpty());
    }

    private static ContainerResponseContext response(MultivaluedMap<String, Object> headers) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getHeaders" -> headers;
            case "getHeaderString" -> firstHeader(headers, (String) args[0]);
            case "toString" -> "fake ContainerResponseContext";
            default -> defaultValue(method.getReturnType());
        };
        return (ContainerResponseContext) Proxy.newProxyInstance(
                ContainerResponseContext.class.getClassLoader(),
                new Class<?>[] { ContainerResponseContext.class }, handler);
    }

    private static String firstHeader(MultivaluedMap<String, Object> headers, String name) {
        Object value = headers.getFirst(name);
        return value != null ? value.toString() : null;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}