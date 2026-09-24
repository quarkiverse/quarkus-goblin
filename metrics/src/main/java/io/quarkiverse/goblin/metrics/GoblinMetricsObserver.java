package io.quarkiverse.goblin.metrics;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultObserver;
import io.quarkus.runtime.StartupEvent;

/**
 * Optional Micrometer integration exposing the assault activity for Prometheus and other metric backends.
 * <p>
 * The following metrics are registered against the application's {@link MeterRegistry} when the
 * {@code quarkus-goblin-metrics} dependency is present:
 * <ul>
 * <li>{@code goblin.assaults.total} -- counter of every fired assault, tagged with its {@code type} and {@code source}
 * ({@code server}, {@code rest-client}, {@code webclient}, {@code database}, {@code messaging});</li>
 * <li>{@code goblin.latency.injected.seconds} -- timer of the delays actually injected, tagged with {@code source};</li>
 * <li>{@code goblin.active} -- gauge that lazily mirrors {@link AssaultEngine#isActive()} (1 when active, 0 otherwise).</li>
 * </ul>
 * All metrics are derived from the {@link AssaultObserver} notifications fired by the engine, so this module only needs
 * to be on the classpath -- it never alters the assault behavior.
 */
@ApplicationScoped
public class GoblinMetricsObserver implements AssaultObserver {

    public static final String TOTAL_METRIC = "goblin.assaults.total";
    public static final String LATENCY_METRIC = "goblin.latency.injected.seconds";
    public static final String ACTIVE_METRIC = "goblin.active";
    public static final String TAG_TYPE = "type";
    public static final String TAG_SOURCE = "source";
    static final String SOURCE_SERVER = "server";
    static final String SOURCE_REST_CLIENT = "rest-client";
    static final String SOURCE_WEB_CLIENT = "webclient";
    static final String SOURCE_DATABASE = "database";
    static final String SOURCE_MESSAGING = "messaging";

    private final MeterRegistry registry;
    private final AssaultEngine engine;

    /**
     * Creates the observer and registers the active-state gauge as a functional gauge over the shared
     * {@link AssaultEngine}, so its value always reflects the current engine state without any bookkeeping.
     *
     * @param registry the application's {@link MeterRegistry}
     * @param engine the shared {@link AssaultEngine}
     */
    public GoblinMetricsObserver(MeterRegistry registry, AssaultEngine engine) {
        this.registry = registry;
        this.engine = engine;
        Gauge.builder(ACTIVE_METRIC, engine, e -> e.isActive() ? 1.0 : 0.0).register(registry);
    }

    void init(@Observes StartupEvent event) {
        // The gauge is functional, so there is nothing to initialise; this observer method merely anchors the bean in
        // the application context (a bean declaring observer methods is never pruned as unused).
    }

    @Override
    public void onActiveChange(boolean active) {
        // The gauge reads the engine state lazily.
    }

    @Override
    public void onAssault(AssaultEngine.AssaultRecord record) {
        String source = sourceOf(record.method());
        Counter.builder(TOTAL_METRIC)
                .tags(TAG_TYPE, record.type(), TAG_SOURCE, source)
                .register(registry)
                .increment();
        if (record.latencyMs() > 0) {
            Timer.builder(LATENCY_METRIC)
                    .tags(TAG_SOURCE, source)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(record.latencyMs(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Derives the assault source tag from the history identifier produced by the engine.
     *
     * @param method the history identifier (e.g. {@code "SampleResource.hello"}, {@code "REST-Client GET ..."},
     *        {@code "WebClient GET ..."}, {@code "Database <default> connection"}, {@code "Messaging ..."})
     * @return the source tag value
     */
    static String sourceOf(String method) {
        if (method != null) {
            if (method.startsWith("REST-Client ")) {
                return SOURCE_REST_CLIENT;
            }
            if (method.startsWith("WebClient ")) {
                return SOURCE_WEB_CLIENT;
            }
            if (method.startsWith("Database ")) {
                return SOURCE_DATABASE;
            }
            if (method.startsWith("Messaging ")) {
                return SOURCE_MESSAGING;
            }
        }
        return SOURCE_SERVER;
    }
}