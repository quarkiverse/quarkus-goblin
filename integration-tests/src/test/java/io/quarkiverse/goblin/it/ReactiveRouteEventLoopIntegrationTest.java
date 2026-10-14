package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Latency on a reactive route never blocks the event loop (issue #64). With a single event loop, concurrent requests
 * delayed by a sleeping loop would be served one after the other; delayed by a Vert.x timer they overlap.
 */
@QuarkusTest
@TestProfile(ReactiveRouteEventLoopIntegrationTest.SingleEventLoopProfile.class)
class ReactiveRouteEventLoopIntegrationTest {

    private static final int REQUESTS = 6;
    private static final long DELAY_MS = 1000;

    @Inject
    AssaultEngine engine;

    @TestHTTPResource("/routes/thread")
    URI threadRoute;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(true);
        cfg.setLatencyMinMs(DELAY_MS);
        cfg.setLatencyMaxMs(DELAY_MS);
        cfg.setTargetLevel(100);
        engine.clearHistory();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    void concurrentDelayedRequestsOverlapOnASingleEventLoop() {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(threadRoute).GET().build();

        // warm the connections and the route up without chaos, so the timing below only measures the delays
        engine.setActive(false);
        IntStream.range(0, REQUESTS).mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList().forEach(CompletableFuture::join);
        engine.setActive(true);

        long start = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> responses = IntStream.range(0, REQUESTS)
                .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList();
        List<HttpResponse<String>> results = responses.stream().map(CompletableFuture::join).toList();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        for (HttpResponse<String> response : results) {
            assertEquals(200, response.statusCode());
            assertEquals("event-loop", response.body());
        }
        // a sleeping loop would need REQUESTS * DELAY_MS (6 s), and any two serialised delays already 2 s: only overlapping
        // timers stay under that
        assertTrue(elapsedMs >= DELAY_MS, "the requests must be delayed, took " + elapsedMs + " ms");
        assertTrue(elapsedMs < 2 * DELAY_MS - 200,
                REQUESTS + " requests delayed by " + DELAY_MS + " ms took " + elapsedMs + " ms on a single event loop");
        assertEquals(REQUESTS, engine.getHistory().stream().filter(record -> "latency".equals(record.type())).count(),
                "got: " + engine.getHistory());
    }

    /**
     * Serves every request on one event loop, so a blocked loop would serialise them.
     */
    public static class SingleEventLoopProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.vertx.event-loops-pool-size", "1");
        }
    }
}
