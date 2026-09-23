package io.quarkiverse.goblin.it;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * End-to-end coverage of the optional OpenTelemetry integration ({@code quarkus-goblin-opentelemetry}): every applied
 * assault emits a {@code goblin.assault} span carrying the assault metadata as {@code goblin.assault.*} attributes,
 * with the internal span kind and linked to the request span that triggered it.
 * <p>
 * The test application runs with {@code %test.quarkus.otel.traces.exporter=cdi} so the {@code SpanExporter} CDI bean
 * ({@link InMemoryTraceSpanExporter}) receives every finished span; the assertions poll it until the spans are
 * exported by the batch processor. Precise attribute derivation is covered on a plain in-process tracer in
 * {@code GoblinTracingObserverTest}.
 */
@QuarkusTest
public class GoblinTracingIntegrationTest {

    @Inject
    AssaultEngine engine;

    @Inject
    InMemoryTraceSpanExporter trace;

    @BeforeEach
    void start() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(false);
        cfg.setExceptionEnabled(false);
        cfg.setHttpStatusEnabled(false);
        cfg.setDependencyDegradationEnabled(false);
        cfg.setClientLatencyEnabled(false);
        cfg.setClientExceptionEnabled(false);
        cfg.setResponseBodyEnabled(false);
        cfg.setResponseHeaderEnabled(false);
        cfg.setLatencyMinMs(100);
        cfg.setLatencyMaxMs(100);
        cfg.setTargetLevel(100);
        engine.clearHistory();
        trace.clear();
    }

    @Test
    public void serverLatencyAssaultProducesSpanLinkedToRequest() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setLatencyEnabled(true);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200);

        SpanData span = awaitAssault(requestStart, s -> "latency".equals(attrOrNull(s, "goblin.assault.type"))
                && hasExportedServerParent(s));
        assertEquals("goblin.assault", span.getName());
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals("SampleResource.hello", attr(span, "goblin.assault.target.method"));
        assertEquals(100L, span.getAttributes().get(AttributeKey.longKey("goblin.assault.latency_ms")),
                "with min=max=100 the injected delay is deterministic");
        assertNotNull(attrOrNull(span, "goblin.assault.config"), "assault spans must carry the config snapshot");
        long requestStartNanos = requestStart * 1_000_000L;
        assertTrue(span.getStartEpochNanos() < requestStartNanos + 100_000_000L,
                "the injected delay must be back-dated towards the request start instead of starting at the post-delay record time");
        assertTrue(span.getEndEpochNanos() - span.getStartEpochNanos() >= 100_000_000L,
                "the injected latency must be attributed to the assault span");
    }

    @Test
    public void httpStatusAssaultUsesConfigStatusCodeAttribute() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setHttpStatusEnabled(true);
        cfg.setHttpStatusCode(503);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503);

        SpanData span = awaitAssault(requestStart, s -> "http-status".equals(attrOrNull(s, "goblin.assault.type"))
                && hasExportedServerParent(s));
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals(503L, span.getAttributes().get(AttributeKey.longKey("goblin.assault.status_code")));
    }

    @Test
    public void webClientLatencyAssaultProducesSpanWithWebClientSource() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(200);

        SpanData span = awaitAssault(requestStart, s -> "webclient".equals(attrOrNull(s, "goblin.assault.source"))
                && hasExportedServerParent(s));
        assertEquals("goblin.assault", span.getName());
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("webclient", attr(span, "goblin.assault.source"));
        assertEquals(100L, span.getAttributes().get(AttributeKey.longKey("goblin.assault.latency_ms")),
                "with min=max=100 the injected delay is deterministic");
        assertTrue(attr(span, "goblin.assault.target.method").startsWith("WebClient"));
    }

    @Test
    public void restClientLatencyAssaultProducesSpanWithRestClientSource() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientLatencyEnabled(true);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/proxy")
                .then()
                .statusCode(200);

        SpanData span = awaitAssault(requestStart, s -> "rest-client".equals(attrOrNull(s, "goblin.assault.source"))
                && hasExportedServerParent(s));
        assertEquals("goblin.assault", span.getName());
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("rest-client", attr(span, "goblin.assault.source"));
        assertEquals(100L, span.getAttributes().get(AttributeKey.longKey("goblin.assault.latency_ms")),
                "with min=max=100 the injected delay is deterministic");
        assertTrue(attr(span, "goblin.assault.target.method").startsWith("REST-Client"));
    }

    @Test
    public void clientExceptionAssaultProducesErrorSpan() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setClientExceptionEnabled(true);
        cfg.setExceptionType("java.lang.IllegalStateException");
        cfg.setExceptionMessage("downstream is in trouble");

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/web-proxy")
                .then()
                .statusCode(500);

        SpanData span = awaitAssault(requestStart, s -> "exception".equals(attrOrNull(s, "goblin.assault.type"))
                && hasExportedServerParent(s));
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("webclient", attr(span, "goblin.assault.source"));
        assertEquals("java.lang.IllegalStateException", attr(span, "goblin.assault.exception"));
        assertTrue(attr(span, "goblin.assault.target.method").startsWith("WebClient"));
    }

    @Test
    public void serverExceptionAssaultProducesErrorSpanLinkedToRequest() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setExceptionType("java.lang.RuntimeException");
        cfg.setExceptionMessage("boom");

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(500);

        SpanData span = awaitAssault(requestStart, s -> "exception".equals(attrOrNull(s, "goblin.assault.type"))
                && hasExportedServerParent(s));
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals("java.lang.RuntimeException", attr(span, "goblin.assault.exception"));
        assertEquals("SampleResource.hello", attr(span, "goblin.assault.target.method"));
    }

    @Test
    public void dependencyDegradationAssaultProducesSpanWithFixedStatusCode() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setDependencyDegradationEnabled(true);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(503);

        SpanData span = awaitAssault(requestStart,
                s -> "dependency-degradation".equals(attrOrNull(s, "goblin.assault.type"))
                        && hasExportedServerParent(s));
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals(503L, span.getAttributes().get(AttributeKey.longKey("goblin.assault.status_code")));
    }

    @Test
    public void responseBodyTruncateAssaultProducesResponseSpanLinkedToRequest() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseBodyEnabled(true);
        cfg.setResponseBodyMode(io.quarkiverse.goblin.ResponseBodyMode.TRUNCATE);
        cfg.setResponseBodyPercentage(50);

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("Content-Length", "13")
                .body(equalTo("hello from Go"));

        SpanData span = awaitAssault(requestStart, s -> "response-body-truncate".equals(attrOrNull(s, "goblin.assault.type"))
                && hasExportedServerParent(s));
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals("SampleResource.hello", attr(span, "goblin.assault.target.method"));
    }

    @Test
    public void responseHeaderSetAssaultProducesResponseSpanLinkedToRequest() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setResponseHeaderEnabled(true);
        cfg.setResponseHeader("X-Goblin", io.quarkiverse.goblin.ResponseHeaderAction.SET, "chaos");

        long requestStart = System.currentTimeMillis();
        RestAssured.given()
                .get("/api/hello")
                .then()
                .statusCode(200)
                .header("X-Goblin", equalTo("chaos"));

        SpanData span = awaitAssault(requestStart,
                s -> "response-header-set:X-Goblin".equals(attrOrNull(s, "goblin.assault.type"))
                        && hasExportedServerParent(s));
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals("SampleResource.hello", attr(span, "goblin.assault.target.method"));
    }

    /**
     * @return {@code true} once the request (SERVER) span matching this assault's parent has been exported, so the
     *         parent-linkage check can be awaited inside the polling loop instead of racing the batch export
     */
    private boolean hasExportedServerParent(SpanData assault) {
        if ("0000000000000000".equals(assault.getParentSpanId())) {
            return false;
        }
        return trace.getFinished().stream()
                .anyMatch(s -> s.getKind() == SpanKind.SERVER && s.getSpanId().equals(assault.getParentSpanId()));
    }

    private SpanData awaitAssault(long requestStartMs, Predicate<SpanData> predicate) {
        long startEpochNanos = requestStartMs * 1_000_000L;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<SpanData> spans = trace.getFinished();
            SpanData match = spans.stream()
                    .filter(s -> s.getName().equals("goblin.assault"))
                    .filter(s -> s.getEndEpochNanos() >= startEpochNanos)
                    .filter(predicate)
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("no goblin.assault span satisfied the predicate, got: " + trace.getFinished());
    }

    private static String attr(SpanData span, String name) {
        String value = span.getAttributes().get(AttributeKey.stringKey(name));
        assertNotNull(value, "expected attribute " + name + " on span " + span.getName() + ": " + span.getAttributes());
        return value;
    }

    private static String attrOrNull(SpanData span, String name) {
        return span.getAttributes().get(AttributeKey.stringKey(name));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}