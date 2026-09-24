package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * Unit tests for {@link GoblinWebClient}. The interceptor is exercised against a real {@code Vertx} {@code WebClient}
 * pointed at a port where nothing listens, so the assault decision, the injected delay and the history recording can be
 * asserted without a remote service.
 */
class GoblinWebClientTest {

    private static final String TARGET = "http://localhost:1/api/hello";

    private static Vertx vertx;
    private static WebClient webClient;

    private final AssaultEngine engine = new AssaultEngine();
    private MutableAssaultConfig config;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx, new WebClientOptions().setDefaultPort(1));
    }

    @AfterAll
    static void stopVertx() {
        webClient.close();
        vertx.close();
    }

    @BeforeEach
    void setUp() {
        engine.setActive(true);
        engine.clearHistory();
        config = new MutableAssaultConfig();
        setMutableConfig(config);
        GoblinWebClient.setEngineForTests(engine);
    }

    @Test
    void enableAttachesTheInterceptorAndIsIdempotent() {
        assertSame(webClient, GoblinWebClient.enable(webClient));
        config.setClientLatencyEnabled(true);
        config.setLatencyRange(5, 10);

        long start = System.nanoTime();
        expectFailure(GoblinWebClient.enable(webClient).getAbs(TARGET));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, engine.getHistory().size(), "repeated enable() must not stack interceptors");
        assertEquals("latency", engine.getHistory().getFirst().type());
        assertTrue(elapsedMs >= 5, "expected at least 5ms delay, got " + elapsedMs + "ms");
    }

    @Test
    void clientLatencyAppliesDelayAndRecords() {
        config.setClientLatencyEnabled(true);
        config.setLatencyRange(100, 100);
        GoblinWebClient.enable(webClient);

        long start = System.currentTimeMillis();
        expectFailure(webClient.getAbs(TARGET));
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getFirst();
        assertEquals("latency", record.type());
        assertEquals("WebClient GET http://localhost:1/api/hello", record.method());
        assertTrue(elapsedMs >= 90, "expected at least 90ms delay, got " + elapsedMs + "ms");
    }

    @Test
    void noAssaultWhenClientAssaultsDisabled() {
        GoblinWebClient.enable(webClient);

        expectFailure(webClient.getAbs(TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void clientExceptionFailsCallAndRecords() {
        config.setClientExceptionEnabled(true);
        config.setExceptionType("java.lang.IllegalStateException");
        config.setExceptionMessage("webclient boom");
        GoblinWebClient.enable(webClient);

        Throwable failure = awaitFailure(webClient.getAbs(TARGET));
        assertTrue(containsMessage(failure, "webclient boom"),
                "expected the configured message in the failure chain, got: " + failure);

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getFirst();
        assertEquals("exception", record.type());
        assertEquals("WebClient GET http://localhost:1/api/hello", record.method());
    }

    @Test
    void latencyThenExceptionBothApply() {
        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);
        config.setLatencyRange(100, 100);
        config.setExceptionType("java.lang.RuntimeException");
        GoblinWebClient.enable(webClient);

        long start = System.currentTimeMillis();
        Throwable failure = awaitFailure(webClient.getAbs(TARGET));
        long elapsedMs = System.currentTimeMillis() - start;

        assertNotNull(failure);
        assertEquals(2, engine.getHistory().size());
        assertEquals("latency", engine.getHistory().get(0).type());
        assertEquals("exception", engine.getHistory().get(1).type());
        assertTrue(elapsedMs >= 90, "expected delay before the exception, got " + elapsedMs + "ms");
    }

    @Test
    void inactiveEngineSkipsAssault() {
        config.setClientLatencyEnabled(true);
        engine.setActive(false);
        GoblinWebClient.enable(webClient);

        expectFailure(webClient.getAbs(TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void zeroTargetLevelSkipsAssault() {
        config.setClientLatencyEnabled(true);
        config.setTargetLevel(0);
        GoblinWebClient.enable(webClient);

        expectFailure(webClient.getAbs(TARGET));

        assertTrue(engine.getHistory().isEmpty());
    }

    /**
     * Awaits the failure of the sent request, asserting that the call did fail (connection refused on port 1, or the
     * injected exception).
     *
     * @param request the request future under test
     */
    private static void expectFailure(io.vertx.ext.web.client.HttpRequest<io.vertx.core.buffer.Buffer> request) {
        assertNotNull(awaitFailure(request));
    }

    private static Throwable awaitFailure(io.vertx.ext.web.client.HttpRequest<io.vertx.core.buffer.Buffer> request) {
        try {
            request.send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            fail("expected the WebClient call to fail");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            fail("unexpected failure while awaiting the WebClient call: " + e);
            return null;
        }
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

    private static boolean containsMessage(Throwable throwable, String message) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    void historyIdentifierDropsTheQueryString() {
        assertEquals("/api/hello", GoblinWebClient.stripQuery("/api/hello?token=abc"));
        assertEquals("/api/hello", GoblinWebClient.stripQuery("/api/hello#part?x"));
        assertEquals("/api/hello", GoblinWebClient.stripQuery("/api/hello"));
        assertEquals("", GoblinWebClient.stripQuery(null));
    }
}
