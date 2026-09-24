package io.quarkiverse.goblin.opentelemetry;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultObserver;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.runtime.StartupEvent;

/**
 * Optional OpenTelemetry integration exposing every applied assault as a trace span.
 * <p>
 * When the {@code quarkus-goblin-opentelemetry} dependency is present, each assault fired by the
 * {@link AssaultEngine} produces a single {@value #SPAN_NAME} span carrying the assault metadata as
 * {@code goblin.assault.*} attributes:
 * <ul>
 * <li>{@code goblin.assault.type} -- the assault type (e.g. {@code latency}, {@code exception},
 * {@code http-status}, {@code response-body-truncate});</li>
 * <li>{@code goblin.assault.source} -- {@code server}, {@code service}, {@code rest-client}, {@code webclient},
 * {@code database} or {@code messaging}, as recorded by the engine;</li>
 * <li>{@code goblin.assault.target.method} -- the recorded method identifier (e.g. {@code SampleResource.hello},
 * {@code REST-Client GET http://...}, {@code WebClient GET http://...});</li>
 * <li>the injected value per type: {@code goblin.assault.latency_ms}, {@code goblin.assault.status_code},
 * {@code goblin.assault.exception}, plus the whole active configuration snapshot in {@code goblin.assault.config}.</li>
 * </ul>
 * The span is started from the current tracing context, so it is automatically linked to the request span that
 * triggered the assault (parent-child relationship) for end-to-end trace correlation. Every assault span uses the
 * INTERNAL span kind (the OpenTelemetry default): it annotates an in-process moment -- the application of the
 * injected delay or failure -- and performs no network call of its own. Labelling it
 * {@link io.opentelemetry.api.trace.SpanKind#CLIENT} or {@link io.opentelemetry.api.trace.SpanKind#SERVER} would be
 * factually wrong and misleading for tracing backends: a CLIENT span with no matching remote SERVER span would
 * fabricate a phantom dependency edge in service graphs, and a SERVER span nested inside the request's own SERVER
 * span would duplicate the client&rarr;server topology.
 * <p>
 * For latency assaults the span start timestamp is back-dated by the injected delay, so the delay itself is
 * attributed to the span in the trace waterfall instead of appearing as an instantaneous replayed call. Back-dating
 * only applies to strictly positive delays; the start timestamp is never negative. Non-latency assaults start at the
 * recorded assault timestamp. An exception assault marks the span {@link StatusCode#ERROR} and adds the configured
 * exception class as an attribute.
 * <p>
 * All spans are derived from the {@link AssaultObserver} notifications fired by the engine, so this module only needs
 * to be on the classpath -- it never alters the assault behavior.
 */
@ApplicationScoped
public class GoblinTracingObserver implements AssaultObserver {

    public static final String SPAN_NAME = "goblin.assault";
    public static final String ATTR_TYPE = "goblin.assault.type";
    public static final String ATTR_SOURCE = "goblin.assault.source";
    public static final String ATTR_TARGET_METHOD = "goblin.assault.target.method";
    public static final String ATTR_LATENCY_MS = "goblin.assault.latency_ms";
    public static final String ATTR_STATUS_CODE = "goblin.assault.status_code";
    public static final String ATTR_EXCEPTION = "goblin.assault.exception";
    public static final String ATTR_CONFIG = "goblin.assault.config";

    private static final long DEPENDENCY_DEGRADATION_STATUS = 503L;

    private final Tracer tracer;
    private final AssaultEngine engine;

    /**
     * Creates the observer backed by the application's {@link Tracer}.
     *
     * @param tracer the OpenTelemetry tracer used to start assault spans
     * @param engine the shared {@link AssaultEngine}, consulted for the injected value attributes
     */
    @Inject
    public GoblinTracingObserver(Tracer tracer, AssaultEngine engine) {
        this.tracer = tracer;
        this.engine = engine;
    }

    void init(@Observes StartupEvent event) {
        // This observer only reacts to engine notifications; the method merely anchors the bean in the application
        // context (a bean declaring observer methods is never pruned as unused).
    }

    @Override
    public void onActiveChange(boolean active) {
        // Engine activations are not traced; only the assaults themselves are.
    }

    @Override
    public void onAssault(AssaultEngine.AssaultRecord record) {
        String source = record.sourceTag();
        SpanBuilder builder = tracer.spanBuilder(SPAN_NAME)
                .setParent(Context.current());
        long latencyMs = record.latencyMs();
        builder.setStartTimestamp(Math.max(0, record.timestamp() - Math.max(0, latencyMs)), TimeUnit.MILLISECONDS);
        Span span = builder.startSpan();
        try {
            span.setAttribute(ATTR_TYPE, record.type());
            span.setAttribute(ATTR_SOURCE, source);
            span.setAttribute(ATTR_TARGET_METHOD, record.method() == null ? "unknown" : record.method());
            span.setAttribute(ATTR_CONFIG, record.configSnapshot());
            applyValueAttributes(span, record, engine != null ? engine.getMutableConfig() : null);
        } finally {
            span.end();
        }
    }

    /**
     * Sets the injected-value attributes specific to the assault type.
     * <p>
     * The configured values are read from the engine's {@link MutableAssaultConfig}, which is {@code null} in plain
     * unit tests; the type-agnostic attributes always apply regardless.
     *
     * @param span the span to enrich
     * @param record the assault record being traced
     * @param config the active configuration, or {@code null} outside a running application
     */
    static void applyValueAttributes(Span span, AssaultEngine.AssaultRecord record, MutableAssaultConfig config) {
        String type = record.type();
        if ("latency".equals(type)) {
            if (record.latencyMs() > 0) {
                span.setAttribute(ATTR_LATENCY_MS, record.latencyMs());
            }
        } else if ("exception".equals(type)) {
            String exceptionClass = config != null ? config.getExceptionType() : "";
            span.setAttribute(ATTR_EXCEPTION, exceptionClass);
            span.setStatus(StatusCode.ERROR, "Goblin injected exception: " + exceptionClass);
        } else if ("http-status".equals(type)) {
            if (config != null) {
                span.setAttribute(ATTR_STATUS_CODE, (long) config.getHttpStatusCode());
            }
        } else if ("dependency-degradation".equals(type)) {
            span.setAttribute(ATTR_STATUS_CODE, DEPENDENCY_DEGRADATION_STATUS);
        }
    }
}