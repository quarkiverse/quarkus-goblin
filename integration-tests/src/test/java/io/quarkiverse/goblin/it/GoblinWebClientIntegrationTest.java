package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.GoblinWebClient;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * End-to-end coverage of client-side assaults applied to outbound Vert.x WebClient calls: on a client armed through
 * {@link GoblinWebClient#enable(WebClient)}, and on the clients the application creates, which Goblin arms at build time.
 * The WebClient targets the application's own test endpoint.
 * <p>
 * The test's own client is created reflectively: Goblin rewrites every {@code WebClient.create(...)} call of the
 * application classes, test classes included, so a direct call would arm it before {@code enable} is ever reached.
 */
@QuarkusTest
public class GoblinWebClientIntegrationTest {

    private static final String DOWNSTREAM = "http://localhost:8081/api/hello";

    @Inject
    AssaultEngine engine;

    @Inject
    Vertx vertx;

    private WebClient client;

    @BeforeEach
    void start() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(200);
        engine.clearHistory();
        client = GoblinWebClient.enable(createOutsideTheRewrite());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void testWebClientCallWorksWhenNoClientAssaultEnabled() {
        assertEquals("hello from Goblin test app", hello());
        assertTrue(engine.getHistory().isEmpty(), "no assault should be recorded");
    }

    @Test
    public void testWebClientLatencyAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(300);
        cfg.setLatencyMaxMs(300);

        long start = System.currentTimeMillis();
        assertEquals("hello from Goblin test app", hello());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 250, "expected at least 250ms WebClient delay, got " + elapsed + "ms");
        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("latency", record.type());
        assertEquals(AssaultType.LATENCY, record.assaultType());
        assertTrue(record.method().startsWith("WebClient GET"),
                "expected a WebClient record, got: " + record.method());
        assertTrue(record.method().contains("/api/hello"),
                "expected the outbound URI in the record, got: " + record.method());
    }

    @Test
    public void testWebClientLatencyDoesNotAffectIncomingRequests() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(1000);
        cfg.setLatencyMaxMs(1000);

        long start = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 900, "incoming requests must not be delayed by WebClient latency, took " + elapsed + "ms");
        assertTrue(engine.getHistory().isEmpty(), "no server-side assault should be recorded");
    }

    @Test
    public void testWebClientExceptionAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(true);
        cfg.setExceptionType("java.lang.IllegalStateException");
        cfg.setExceptionMessage("downstream is in trouble");

        Throwable thrown = assertThrows(CompletionException.class, this::hello);
        assertTrue(containsMessage(thrown.getCause(), "downstream is in trouble"),
                "expected the configured message in the exception chain, got: " + thrown);

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("exception", record.type());
        assertEquals(AssaultType.EXCEPTION, record.assaultType());
        assertTrue(record.method().startsWith("WebClient GET"),
                "expected a WebClient record, got: " + record.method());
        assertTrue(record.method().contains("/api/hello"),
                "expected the outbound URI in the record, got: " + record.method());
    }

    @Test
    public void testWebClientCombinedLatencyAndException() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setClientExceptionEnabled(true);
        cfg.setLatencyMinMs(200);
        cfg.setLatencyMaxMs(200);
        cfg.setExceptionType("java.lang.RuntimeException");

        long start = System.currentTimeMillis();
        assertThrows(CompletionException.class, this::hello);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 150, "expected the delay before the WebClient exception, got " + elapsed + "ms");
        assertEquals(2, engine.getHistory().size());
        assertEquals("latency", engine.getHistory().getFirst().type());
        assertEquals(AssaultType.LATENCY, engine.getHistory().getFirst().assaultType());
        assertEquals("exception", engine.getHistory().get(1).type());
        assertEquals(AssaultType.EXCEPTION, engine.getHistory().get(1).assaultType());
    }

    @Test
    public void testWebClientAssaultRespectsTargetLevel() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setClientExceptionEnabled(true);
        cfg.setTargetLevel(0);

        assertEquals("hello from Goblin test app", hello());
        assertTrue(engine.getHistory().isEmpty(), "level 0 must spare outbound WebClient calls");
    }

    @Test
    public void testWebProxyEndpointCallsWebClient() {
        String body = RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertEquals("hello from Goblin test app", body);
        assertTrue(engine.getHistory().isEmpty(), "no assault should be recorded");
    }

    @Test
    public void testWebProxyEndpointLatencyAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);
        cfg.setLatencyMinMs(300);
        cfg.setLatencyMaxMs(300);

        long start = System.currentTimeMillis();
        String body = RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("hello from Goblin test app", body);
        assertTrue(elapsed >= 250, "expected at least 250ms delay on the WebClient hop, got " + elapsed + "ms");
        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("latency", record.type());
        assertEquals(AssaultType.LATENCY, record.assaultType());
        assertTrue(record.method().startsWith("WebClient GET"),
                "expected a WebClient record, got: " + record.method());
    }

    @Test
    public void testWebProxyEndpointExceptionAssault() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(true);
        cfg.setExceptionType("java.lang.IllegalStateException");
        cfg.setExceptionMessage("downstream is in trouble");

        RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(500);

        assertEquals(1, engine.getHistory().size());
        AssaultEngine.AssaultRecord record = engine.getHistory().getLast();
        assertEquals("exception", record.type());
        assertEquals(AssaultType.EXCEPTION, record.assaultType());
        assertTrue(record.method().startsWith("WebClient GET"),
                "expected a WebClient record, got: " + record.method());
    }

    @Test
    public void aClientCreatedOutsideTheApplicationCodeIsLeftAloneUntilEnabled() {
        engine.getMutableConfig().setClientExceptionEnabled(true);
        client.close();
        client = createOutsideTheRewrite();

        assertEquals("hello from Goblin test app", hello(), "a client Goblin did not arm must not be assaulted");
        assertTrue(engine.getHistory().isEmpty(), "no assault should be recorded, got: " + engine.getHistory());

        GoblinWebClient.enable(client);
        assertThrows(CompletionException.class, this::hello, "once enabled, the client must be assaulted");
        assertFalse(engine.getHistory().isEmpty(), "the assault must be recorded once the client is enabled");
    }

    /**
     * Creates a client the build-time rewrite does not see: a reflective call is not a {@code WebClient.create(...)}
     * call site, so the client stays unarmed until {@link GoblinWebClient#enable(WebClient)}.
     */
    private WebClient createOutsideTheRewrite() {
        try {
            return (WebClient) WebClient.class.getMethod("create", Vertx.class, WebClientOptions.class)
                    .invoke(null, vertx, new WebClientOptions().setConnectTimeout(5_000));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hello() {
        try {
            HttpResponse<Buffer> response = client.getAbs(DOWNSTREAM).send()
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return response.bodyAsString();
        } catch (Exception e) {
            throw new CompletionException(e);
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
    public void webClientsTheApplicationCreatesAreArmedWithoutAnyGoblinCall() {
        engine.getMutableConfig().setClientExceptionEnabled(true);

        // SampleResource creates both clients with a plain WebClient.create(...): Goblin armed them at build time
        for (String endpoint : List.of("/api/web-proxy", "/api/mutiny-web-proxy")) {
            engine.clearHistory();
            RestAssured.given()
                    .get(endpoint)
                    .then()
                    .statusCode(500);
            assertTrue(engine.getHistory().stream().anyMatch(record -> "exception".equals(record.type())
                    && record.method().startsWith("WebClient GET") && record.method().endsWith("/api/hello")),
                    endpoint + " must have been assaulted by the client exception, got: " + engine.getHistory());
        }
    }
}
