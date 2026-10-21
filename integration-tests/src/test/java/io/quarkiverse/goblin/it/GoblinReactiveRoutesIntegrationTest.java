package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.*;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * The HTTP_IN layer on reactive routes (issue #64): {@link SampleRoutes} gets the same assaults as a REST resource,
 * with the same history labels, without any change in the route methods.
 */
@QuarkusTest
class GoblinReactiveRoutesIntegrationTest {

    @Inject
    AssaultEngine engine;

    @TestHTTPResource("/")
    URL server;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyMinMs(200);
        cfg.setLatencyMaxMs(250);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    void latencyOnAnEventLoopRouteDelaysTheResponse() {
        engine.getMutableConfig().setLatencyEnabled(true);

        long start = System.nanoTime();
        RestAssured.given().get("/routes/thread").then().statusCode(200).body(equalTo("event-loop"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // the route runs on the event loop, where Goblin never sleeps: the delay comes from a Vert.x timer
        assertTrue(elapsedMs >= 200, "the route must be delayed by 200-250 ms, took " + elapsedMs + " ms");
        AssaultEngine.AssaultRecord record = single("SampleRoutes.thread");
        assertEquals("latency", record.type());
        assertEquals(AssaultType.LATENCY, record.assaultType());
        assertEquals("server", record.sourceTag());
        assertTrue(record.latencyMs() >= 200 && record.latencyMs() <= 250, "got: " + record.latencyMs());
    }

    @Test
    void latencyOnABlockingRouteDelaysTheResponse() {
        engine.getMutableConfig().setLatencyEnabled(true);

        long start = System.nanoTime();
        RestAssured.given().get("/routes/blocking").then().statusCode(200)
                .body(equalTo("blocking hello from Goblin route"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 200, "the route must be delayed by 200-250 ms, took " + elapsedMs + " ms");
        assertEquals("latency", single("SampleRoutes.blocking").type());
        assertEquals(AssaultType.LATENCY, single("SampleRoutes.blocking").assaultType());
    }

    @Test
    void exceptionIsAnsweredByTheFailureHandler() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setExceptionMessage("Goblin route chaos");

        // the failure route answers the injected exception: it is never assaulted itself
        RestAssured.given().get("/routes/hello").then().statusCode(500)
                .body(equalTo("route failure handled: Goblin route chaos"));
        assertEquals("exception", single("SampleRoutes.hello").type());
        assertEquals(AssaultType.EXCEPTION, single("SampleRoutes.hello").assaultType());
    }

    @Test
    void latencyThenExceptionAfterTheTimer() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setExceptionEnabled(true);
        cfg.setExceptionMessage("late chaos");

        RestAssured.given().get("/routes/hello").then().statusCode(500)
                .body(equalTo("route failure handled: late chaos"));
        List<String> types = engine.getHistory().stream().map(AssaultEngine.AssaultRecord::type).toList();
        assertEquals(List.of("latency", "exception"), types);
    }

    @Test
    void httpStatusAbortsATextRoute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(418);
        cfg.setHttpStatusMessage("teapot chaos");

        RestAssured.given().get("/routes/hello").then().statusCode(418)
                .contentType(startsWith("text/plain"))
                .body(equalTo("teapot chaos"));
        assertEquals("http-status", single("SampleRoutes.hello").type());
        assertEquals(AssaultType.HTTP_STATUS, single("SampleRoutes.hello").assaultType());
    }

    @Test
    void httpStatusAbortsAJsonRouteWithAJsonBody() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(429);
        cfg.setHttpStatusMessage("slow down");

        RestAssured.given().get("/routes/json").then().statusCode(429)
                .contentType(startsWith("application/json"))
                .body("message", equalTo("slow down"))
                .body("code", equalTo(429));
    }

    @Test
    void dependencyDegradationAnswers503() {
        engine.getMutableConfig().setDependencyDegradationEnabled(true);

        RestAssured.given().get("/routes/hello").then().statusCode(503)
                .body(equalTo("Dependency unavailable (Goblin chaos)"));
        assertEquals("dependency-degradation", single("SampleRoutes.hello").type());
        assertEquals(AssaultType.DEPENDENCY_DEGRADATION, single("SampleRoutes.hello").assaultType());
    }

    @Test
    void aRestRequestIsAssaultedExactlyOnce() {
        engine.getMutableConfig().setLatencyEnabled(true);

        RestAssured.given().get("/api/hello").then().statusCode(200);

        assertEquals(1, engine.getHistory().size(), "got: " + engine.getHistory());
        assertEquals("SampleResource.hello", engine.getHistory().get(0).method());
    }

    @Test
    void serviceLayerReachesTheBeanBelowARoute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayers(List.of(ChaosLayer.SERVICE));
        cfg.setExceptionEnabled(true);
        cfg.setExceptionMessage("service chaos");

        RestAssured.given().get("/routes/service").then().statusCode(500)
                .body(equalTo("route failure handled: service chaos"));
        assertEquals("exception", single("io.quarkiverse.goblin.it.SampleService.hello").type());
        assertEquals(AssaultType.EXCEPTION, single("io.quarkiverse.goblin.it.SampleService.hello").assaultType());
    }

    @Test
    void routesAreUntouchedWhenHttpInIsNotArmed() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayers(List.of(ChaosLayer.SERVICE));
        cfg.setHttpStatusEnabled(true);

        RestAssured.given().get("/routes/hello").then().statusCode(200).body(equalTo("hello from Goblin route"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    void routesAreUntouchedWhenChaosIsOff() {
        engine.getMutableConfig().setHttpStatusEnabled(true);
        engine.setActive(false);

        RestAssured.given().get("/routes/hello").then().statusCode(200).body(equalTo("hello from Goblin route"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    void aDelayedRouteRunsInsideItsRequestContextAndFiltersAreNeverAssaulted() {
        engine.getMutableConfig().setLatencyEnabled(true);

        // the @RouteFilter set a request-scoped value before the delay; the route reads it after the timer
        RestAssured.given().get("/routes/trace").then().statusCode(200)
                .header("X-Goblin-Filter", "seen")
                .body(equalTo("filtered"));
        assertEquals("latency", single("SampleRoutes.trace").type());
        assertEquals(AssaultType.LATENCY, single("SampleRoutes.trace").assaultType());
    }

    @Test
    void aPassThroughRouteInFrontOfARestResourceAssaultsTheRequestOnce() {
        engine.getMutableConfig().setLatencyEnabled(true);

        RestAssured.given().get("/api/passthrough/hello").then().statusCode(200)
                .header("X-Goblin-Pass-Through", "seen")
                .body(equalTo("hello behind a pass-through route"));
        assertEquals("latency", single("SampleRouteFilters.passThrough").type());
        assertEquals(AssaultType.LATENCY, single("SampleRouteFilters.passThrough").assaultType());
    }

    @Test
    void httpStatusAfterTheTimer() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(418);

        RestAssured.given().get("/routes/thread").then().statusCode(418);
        List<String> types = engine.getHistory().stream().map(AssaultEngine.AssaultRecord::type).toList();
        assertEquals(List.of("latency", "http-status"), types);
    }

    @Test
    void aRequestBodySurvivesTheDelay() {
        engine.getMutableConfig().setLatencyEnabled(true);

        RestAssured.given().body("payload").post("/routes/echo").then().statusCode(200)
                .body(equalTo("echo: payload"));
        assertEquals("latency", single("SampleRoutes.echo").type());
        assertEquals(AssaultType.LATENCY, single("SampleRoutes.echo").assaultType());
    }

    @Test
    void targetLevelZeroLeavesRoutesAlone() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setTargetLevel(0);

        RestAssured.given().get("/routes/hello").then().statusCode(200).body(equalTo("hello from Goblin route"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    void responseBodyTruncateCutsTheRouteResult() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        io.restassured.response.Response response = RestAssured.given().get("/routes/hello");
        response.then().statusCode(200);
        String body = response.asString();
        assertEquals(Integer.toString(body.length()), response.header("Content-Length"),
                "TRUNCATE keeps the response well-framed");
        assertTrue(body.length() < "hello from Goblin route".length() && "hello from Goblin route".startsWith(body),
                "got: " + body);
        assertEquals("response-body-truncate", single("SampleRoutes.hello").type());
        assertEquals(AssaultType.RESPONSE_BODY, single("SampleRoutes.hello").assaultType());
    }

    @Test
    void responseBodyTruncateBreaksAJsonRoute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        String body = RestAssured.given().get("/routes/json").then().statusCode(200).extract().asString();
        String full = "{\"greeting\":\"hello from Goblin route\"}";
        assertEquals(full.substring(0, body.length()), body);
        assertTrue(body.length() < full.length(), "the JSON written by the route must be cut, got: " + body);
    }

    @Test
    void responseBodyInflateAdvertisesTheOriginalLength() throws Exception {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.INFLATE);
        cfg.setResponseBodyPercentage(200);

        Map<String, String> headers = new HashMap<>();
        byte[] body = rawGet("/routes/hello", headers);

        String original = "hello from Goblin route";
        assertEquals(Integer.toString(original.length()), headers.get("content-length"),
                "INFLATE must advertise the original, smaller length");
        assertTrue(body.length > original.length(), "the emitted payload must be larger, got " + body.length);
        assertTrue(new String(body, StandardCharsets.UTF_8).startsWith(original));
    }

    @Test
    void responseBodyAppliesAfterTheDelayToo() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        String body = RestAssured.given().get("/routes/hello").then().statusCode(200).extract().asString();
        assertTrue("hello from Goblin route".startsWith(body) && body.length() < 23, "got: " + body);
        List<String> types = engine.getHistory().stream().map(AssaultEngine.AssaultRecord::type).toList();
        assertEquals(List.of("latency", "response-body-truncate"), types);
    }

    @Test
    void responseHeaderSetAndRemoveOnARoute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");
        cfg.setResponseHeader("Content-Type", ResponseHeaderAction.REMOVE, "");

        RestAssured.given().get("/routes/hello").then().statusCode(200)
                .header("X-Goblin", equalTo("chaos"))
                .header("Content-Type", org.hamcrest.Matchers.nullValue())
                .body(equalTo("hello from Goblin route"));
        List<String> types = engine.getHistory().stream().map(AssaultEngine.AssaultRecord::type).sorted().toList();
        assertEquals(List.of("response-header-remove:Content-Type", "response-header-set:X-Goblin"), types);
    }

    @Test
    void responseHeaderAlsoReachesAnAbortedRoute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("Retry-After", ResponseHeaderAction.SET, "120");

        RestAssured.given().get("/routes/hello").then().statusCode(503).header("Retry-After", equalTo("120"));
    }

    @Test
    void responseAssaultsLeaveFiltersAndOtherPathsAlone() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayers(List.of(ChaosLayer.SERVICE));
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");

        RestAssured.given().get("/routes/hello").then().statusCode(200)
                .header("X-Goblin", org.hamcrest.Matchers.nullValue());
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    void aZeroLatencyDrawKeepsTheOtherAssaults() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(0);
        cfg.setLatencyMaxMs(0);
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(418);

        // Vert.x refuses a 0 ms timer: the hook must not give up the request
        RestAssured.given().get("/routes/thread").then().statusCode(418);
        assertEquals(List.of("latency", "http-status"), types());
    }

    @Test
    void anAbortBodyIsRewrittenLikeOnARestResource() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(418);
        cfg.setHttpStatusMessage("teapot chaos");
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        String body = RestAssured.given().get("/routes/hello").then().statusCode(418).extract().asString();
        assertTrue(body.length() < "teapot chaos".length() && "teapot chaos".startsWith(body), "got: " + body);
        assertEquals(List.of("http-status", "response-body-truncate"), types());
    }

    @Test
    void aRouteFailingAfterTheDelayIsAnsweredByTheFailureHandler() {
        engine.getMutableConfig().setLatencyEnabled(true);

        RestAssured.given().get("/routes/boom").then().statusCode(500).body(equalTo("route failure handled: boom"));
        assertEquals(List.of("latency"), types());
    }

    @Test
    void responseBodyFollowsAFluentChain() {
        truncateHalf();

        io.restassured.response.Response response = RestAssured.given().get("/routes/fluent");
        response.then().statusCode(201).header("X-Fluent", "yes");
        String body = response.asString();
        assertTrue(body.length() < 30 && "fluent hello from Goblin route".startsWith(body), "got: " + body);
    }

    @Test
    void responseBodyReachesRoutingContextDefaultMethods() {
        truncateHalf();

        String body = RestAssured.given().get("/routes/json-default").then().statusCode(200).extract().asString();
        String full = "{\"greeting\":\"hello\"}";
        assertTrue(body.length() < full.length() && full.startsWith(body), "got: " + body);
    }

    @Test
    void responseBodyOnAUniRouteAfterTheDelay() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);
        truncateHalf();

        String body = RestAssured.given().get("/routes/uni").then().statusCode(200).extract().asString();
        assertTrue(body.length() < 27 && "uni hello from Goblin route".startsWith(body), "got: " + body);
        assertEquals(List.of("latency", "response-body-truncate"), types());
    }

    @Test
    void aStreamedRouteIsLeftUntouched() {
        truncateHalf();

        RestAssured.given().get("/routes/multi").then().statusCode(200).body(equalTo("streamed hello"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    void aHeadRequestIsAnsweredWithoutABody() {
        truncateHalf();

        RestAssured.given().head("/routes/hello").then().statusCode(200).body(equalTo(""));
    }

    @Test
    void responseHeaderReachesTheFailureHandlerAnswer() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");

        RestAssured.given().get("/routes/hello").then().statusCode(500).header("X-Goblin", equalTo("chaos"));
    }

    @Test
    void aPassThroughRouteDecisionReachesTheServiceBelowTheRestResource() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLayers(List.of(ChaosLayer.SERVICE));
        cfg.setExceptionEnabled(true);

        // the route resolves the layer on the event loop, the resource and its bean run on a worker thread
        RestAssured.given().get("/api/passthrough/service").then().statusCode(500);
        assertEquals("exception", single("io.quarkiverse.goblin.it.SampleService.hello").type());
        assertEquals(AssaultType.EXCEPTION, single("io.quarkiverse.goblin.it.SampleService.hello").assaultType());
    }

    @Test
    void aPassThroughRouteRewritesTheBodyOfTheRestResourceBehindIt() {
        truncateHalf();

        String body = RestAssured.given().get("/api/passthrough/hello").then().statusCode(200).extract().asString();
        String full = "hello behind a pass-through route";
        assertTrue(body.length() < full.length() && full.startsWith(body), "got: " + body);
        assertEquals("response-body-truncate", single("SampleRouteFilters.passThrough").type());
        assertEquals(AssaultType.RESPONSE_BODY, single("SampleRouteFilters.passThrough").assaultType());
    }

    private void truncateHalf() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);
    }

    private List<String> types() {
        return engine.getHistory().stream().map(AssaultEngine.AssaultRecord::type).toList();
    }

    /**
     * Reads a response without trusting its {@code Content-Length}, to observe the INFLATE mismatch.
     */
    private byte[] rawGet(String path, Map<String, String> headers) throws Exception {
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            socket.setSoTimeout(2000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            byte[] raw = socket.getInputStream().readAllBytes();
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int separator = text.indexOf("\r\n\r\n");
            for (String line : text.substring(0, separator).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            return Arrays.copyOfRange(raw, separator + 4, raw.length);
        }
    }

    private AssaultEngine.AssaultRecord single(String method) {
        List<AssaultEngine.AssaultRecord> records = engine.getHistory();
        assertEquals(1, records.size(), "exactly one assault expected, got: " + records);
        assertEquals(method, records.get(0).method());
        return records.get(0);
    }
}
