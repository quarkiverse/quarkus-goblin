package io.quarkiverse.goblin.it;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Test {@link SpanExporter} that keeps the finished spans in memory so the integration tests can assert the assault
 * spans emitted by {@code quarkus-goblin-opentelemetry}. Picked up by Quarkus OpenTelemetry because the application
 * runs with {@code quarkus.otel.traces.exporter=cdi} and this bean is the only {@code SpanExporter} on the classpath.
 */
@ApplicationScoped
public class InMemoryTraceSpanExporter implements SpanExporter {

    private final CopyOnWriteArrayList<SpanData> finished = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        finished.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /**
     * @return a snapshot of the finished spans exported so far
     */
    public List<SpanData> getFinished() {
        return new ArrayList<>(finished);
    }

    /**
     * Discards every span exported so far.
     */
    public void clear() {
        finished.clear();
    }
}