package io.quarkiverse.goblin.internal.route;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.ResponseBodyMode;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;
import io.quarkiverse.goblin.internal.MutableAssaultConfigImpl;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * The response body wrapper of reactive routes (issue #64), against fake Vert.x objects recording what reaches the
 * real response.
 */
class RouteResponseTest {

    private final AssaultEngineImpl engine = new AssaultEngineImpl();

    /** What the fake response received: the payloads ended with, and the methods called. */
    private final List<byte[]> ended = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();

    @Test
    void truncatesAStringPayloadAndDeclaresItsLength() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        context.response().end("0123456789");

        assertEquals("01234", new String(ended.get(0), StandardCharsets.UTF_8));
        assertEquals("5", headers.get("Content-Length"));
        assertEquals("response-body-truncate", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.RESPONSE_BODY, engine.getHistory().getFirst().assaultType());
        assertEquals("Routes.hello", engine.getHistory().getFirst().method());
    }

    @Test
    void inflateKeepsAnAlreadyDeclaredLength() {
        RoutingContext context = wrap(ResponseBodyMode.INFLATE, 200);
        headers.set("Content-Length", "10");

        context.response().end(Buffer.buffer("0123456789"));

        assertEquals("10", headers.get("Content-Length"));
        assertTrue(ended.get(0).length > 10);
    }

    @Test
    void encodesAStringWithItsDeclaredCharset() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 100);

        context.response().end("é", "ISO-8859-1");

        assertEquals(1, ended.get(0).length, "ISO-8859-1 encodes é on one byte");
    }

    @Test
    void aStreamedResponseIsLeftUntouched() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        context.response().write("chunk");
        context.response().end("0123456789");

        assertEquals("0123456789", new String(ended.get(0), StandardCharsets.UTF_8));
        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void onlyTheFirstEndIsRewritten() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        context.response().end("0123456789");
        context.response().end("abcdefghij");

        assertEquals("abcdefghij", new String(ended.get(1), StandardCharsets.UTF_8));
        assertEquals(1, engine.getHistory().size());
    }

    @Test
    void fluentChainsStayOnTheWrappers() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        HttpServerResponse response = context.response();
        assertSame(response, response.setStatusCode(201), "the response returns itself, not the real response");
        assertSame(context, context.put("key", "value"), "the context returns itself, not the real context");
        assertTrue(calls.contains("setStatusCode"));
    }

    @Test
    void defaultMethodsWriteThroughTheWrapper() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        context.end("0123456789");

        assertEquals("01234", new String(ended.get(0), StandardCharsets.UTF_8));
    }

    @Test
    void theWrapperHasItsOwnIdentity() {
        RoutingContext context = wrap(ResponseBodyMode.TRUNCATE, 50);

        assertTrue(context.equals(context));
        assertFalse(context.equals(new Object()));
        assertEquals(System.identityHashCode(context), context.hashCode());
    }

    @Test
    void headerRulesAloneKeepTheRealContext() {
        RoutingContext real = fakeContext();
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseHeaderEnabled(true);

        assertSame(real, RouteResponse.wrap(real, config, engine, "Routes.hello"));
        assertTrue(calls.contains("addHeadersEndHandler"));
    }

    private RoutingContext wrap(ResponseBodyMode mode, int percentage) {
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(mode);
        config.setResponseBodyPercentage(percentage);
        RoutingContext wrapped = RouteResponse.wrap(fakeContext(), config, engine, "Routes.hello");
        return wrapped;
    }

    private RoutingContext fakeContext() {
        HttpServerResponse response = fake(HttpServerResponse.class, (proxy, method, args) -> {
            String name = method.getName();
            calls.add(name);
            if (name.equals("headers")) {
                return headers;
            }
            if (name.equals("putHeader")) {
                headers.set(args[0].toString(), args[1].toString());
                return proxy;
            }
            if (name.equals("end")) {
                ended.add(args[0] instanceof Buffer buffer ? buffer.getBytes()
                        : ((String) args[0]).getBytes(StandardCharsets.UTF_8));
                return null;
            }
            return name.equals("setStatusCode") ? proxy : null;
        });
        return fake(RoutingContext.class, (proxy, method, args) -> {
            String name = method.getName();
            calls.add(name);
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (name.equals("response")) {
                return response;
            }
            if (name.equals("put")) {
                return proxy;
            }
            return name.equals("addHeadersEndHandler") ? 0 : null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RouteResponseTest.class.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
