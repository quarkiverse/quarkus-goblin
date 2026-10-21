package io.quarkiverse.goblin.internal.assault;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;
import io.quarkiverse.goblin.internal.MutableAssaultConfigImpl;

/**
 * Unit tests for {@link ResponseHeaderTransformer}, verifying the set/remove semantics and the history recording on a
 * fake JAX-RS response context backed by a real {@link AssaultEngineImpl}.
 */
class ResponseHeaderTransformerTest {

    private final AssaultEngineImpl engine = new AssaultEngineImpl();

    @Test
    void setForcesHeaderOnEvenWhenAbsent() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("chaos", headers.getFirst("X-Goblin"));
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-header-set:X-Goblin", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.RESPONSE_HEADER, engine.getHistory().getFirst().assaultType());
    }

    @Test
    void setReplacesAnExistingValue() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "new");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Goblin", "old");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("new", headers.getFirst("X-Goblin"));
    }

    @Test
    void removeDeletesAnExistingHeader() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Goblin", "chaos");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertNull(headers.getFirst("X-Goblin"));
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-header-remove:X-Goblin", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.RESPONSE_HEADER, engine.getHistory().getFirst().assaultType());
    }

    @Test
    void setReplacesAnExistingValueIgnoringCase() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "new");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("x-goblin", "old");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertEquals("new", headers.getFirst("X-Goblin"));
        assertNull(headers.getFirst("x-goblin"), "the case-variant duplicate must be gone");
        assertEquals(1, headers.size());
    }

    @Test
    void removeDeletesAnExistingHeaderIgnoringCase() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("x-goblin", "chaos");

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertTrue(headers.isEmpty());
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-header-remove:X-Goblin", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.RESPONSE_HEADER, engine.getHistory().getFirst().assaultType());
    }

    @Test
    void removeLeavesAbsentHeaderUnrecorded() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.REMOVE, "");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void everyConfiguredRuleIsAppliedAndRecorded() {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
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

        ResponseHeaderTransformer.apply(response(headers), new MutableAssaultConfigImpl(), engine, "Api.hello");

        assertTrue(headers.isEmpty());
        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void skipsRuleWithUnsafeValueInjectedOutsideTheConfigApi() throws Exception {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        injectRule(config, "X-Goblin", "chaos\nInjected: true");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ResponseHeaderTransformer.apply(response(headers), config, engine, "Api.hello");

        assertTrue(headers.isEmpty(), "a value that cannot be emitted as a header must be skipped");
        assertTrue(engine.getHistory().isEmpty());
    }

    private static void injectRule(MutableAssaultConfigImpl config, String name, String value) {
        config.putRawHeaderRuleForTests(name, new MutableAssaultConfig.HeaderRule(ResponseHeaderAction.SET, value));
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
