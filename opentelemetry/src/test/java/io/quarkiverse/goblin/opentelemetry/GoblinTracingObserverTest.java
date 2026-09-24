package io.quarkiverse.goblin.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.MutableAssaultConfig;

class GoblinTracingObserverTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final Tracer tracer = tracerProvider.get("goblin-test");
    private final AssaultEngine engine = new AssaultEngine();
    private final GoblinTracingObserver observer = new GoblinTracingObserver(tracer, engine);

    @AfterEach
    void reset() {
        exporter.reset();
    }

    @Test
    void serverLatencyAssaultProducesSpanWithAttributes() {
        long now = System.currentTimeMillis();
        observer.onAssault(record("SampleResource.hello", "latency", 250, now, "latency enabled (100 - 500 ms)"));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.getFirst();
        assertEquals(GoblinTracingObserver.SPAN_NAME, span.getName());
        assertEquals(SpanKind.INTERNAL, span.getKind(), "an assault span annotates an in-process moment, no I/O of its own");
        assertEquals("latency", attr(span, "goblin.assault.type"));
        assertEquals("server", attr(span, "goblin.assault.source"));
        assertEquals("SampleResource.hello", attr(span, "goblin.assault.target.method"));
        assertEquals(250L, longAttr(span, "goblin.assault.latency_ms"));
        assertEquals("latency enabled (100 - 500 ms)", attr(span, "goblin.assault.config"));
    }

    @Test
    void latencySpanStartIsBackDatedByTheInjectedDelay() {
        long now = System.currentTimeMillis();
        observer.onAssault(record("SampleResource.hello", "latency", 250, now, "snapshot"));

        assertEquals((now - 250) * 1_000_000L, exporter.getFinishedSpanItems().getFirst().getStartEpochNanos());
    }

    @Test
    void restClientAssaultSourceIsRecorded() {
        observer.onAssault(
                record(AssaultSource.REST_CLIENT, "REST-Client GET http://localhost:8081/api/hello", "latency", 100));

        SpanData span = singleSpan();
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("rest-client", attr(span, "goblin.assault.source"));
    }

    @Test
    void webClientAssaultSourceIsRecorded() {
        observer.onAssault(record(AssaultSource.WEBCLIENT, "WebClient GET http://localhost:8081/api/hello", "latency", 100));

        SpanData span = singleSpan();
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("webclient", attr(span, "goblin.assault.source"));
    }

    @Test
    void assaultSpanIsChildOfCurrentRequestSpan() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            observer.onAssault(record("SampleResource.hello", "http-status", 0));
        }
        parent.end();

        SpanData assault = finished("goblin.assault");
        SpanData parentData = finished("parent");
        assertEquals(parentData.getTraceId(), assault.getTraceId());
        assertEquals(parentData.getSpanId(), assault.getParentSpanId());
    }

    @Test
    void assaultWithoutParentIsARootSpan() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        parent.end();
        observer.onAssault(record("SampleResource.hello", "http-status", 0));

        SpanData assault = finished("goblin.assault");
        assertEquals("0000000000000000", assault.getParentSpanId(),
                "an assault outside any request scope must be a root span");
    }

    @Test
    void exceptionAssaultValueAttributesMarkError() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setExceptionType("java.lang.IllegalStateException");
        Span span = tracer.spanBuilder(GoblinTracingObserver.SPAN_NAME).startSpan();
        GoblinTracingObserver.applyValueAttributes(span, record("SampleResource.hello", "exception", 0), config);
        span.end();

        SpanData data = singleSpan();
        assertEquals("java.lang.IllegalStateException", attr(data, "goblin.assault.exception"));
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
    }

    @Test
    void httpStatusValueAttributeReflectsConfig() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setHttpStatusCode(503);
        Span span = tracer.spanBuilder(GoblinTracingObserver.SPAN_NAME).startSpan();
        GoblinTracingObserver.applyValueAttributes(span, record("SampleResource.hello", "http-status", 0), config);
        span.end();

        assertEquals(503L, longAttr(singleSpan(), "goblin.assault.status_code"));
    }

    @Test
    void dependencyDegradationValueAttributeIsFixed503() {
        Span span = tracer.spanBuilder(GoblinTracingObserver.SPAN_NAME).startSpan();
        GoblinTracingObserver.applyValueAttributes(span, record("SampleResource.hello", "dependency-degradation", 0), null);
        span.end();

        assertEquals(503L, longAttr(singleSpan(), "goblin.assault.status_code"));
    }

    @Test
    void nonLatencySpanIsNotBackDated() {
        long now = System.currentTimeMillis();
        observer.onAssault(record("SampleResource.hello", "http-status", 0, now, "snapshot"));

        assertEquals(now * 1_000_000L, singleSpan().getStartEpochNanos());
    }

    @Test
    void nonPositiveLatencyIsNeverBackDated() {
        long now = System.currentTimeMillis();
        observer.onAssault(record("SampleResource.hello", "latency", -50, now, "snapshot"));

        assertEquals(now * 1_000_000L, singleSpan().getStartEpochNanos(),
                "a non-positive delay must not shift the span start into the past");
    }

    @Test
    void sourceAttributeComesFromTheRecord() {
        assertEquals("service", record(AssaultSource.SERVICE, "com.acme.Service.call", "exception", 0).sourceTag());
        assertEquals("database", record(AssaultSource.DATABASE, "Database <default> connection", "latency", 1).sourceTag());
        assertEquals("server", record(null, "SampleResource.hello", "latency", 1).sourceTag(),
                "a record without a source is a server-side assault");
    }

    private SpanData singleSpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "expected exactly one span, got: " + spans);
        return spans.getFirst();
    }

    private SpanData finished(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finished span named " + name));
    }

    private static String attr(SpanData span, String name) {
        String value = span.getAttributes().get(AttributeKey.stringKey(name));
        assertNotNull(value, "expected attribute " + name + " on span " + span.getName());
        return value;
    }

    private static long longAttr(SpanData span, String name) {
        Long value = span.getAttributes().get(AttributeKey.longKey(name));
        assertNotNull(value, "expected attribute " + name + " on span " + span.getName());
        return value;
    }

    private static AssaultEngine.AssaultRecord record(AssaultSource source, String method, String type, long latencyMs) {
        return new AssaultEngine.AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, "snapshot", source);
    }

    private static AssaultEngine.AssaultRecord record(String method, String type, long latencyMs) {
        return record(method, type, latencyMs, System.currentTimeMillis(), "snapshot");
    }

    private static AssaultEngine.AssaultRecord record(String method, String type, long latencyMs, long timestamp,
            String configSnapshot) {
        return new AssaultEngine.AssaultRecord(method, type, timestamp, latencyMs, configSnapshot);
    }
}
