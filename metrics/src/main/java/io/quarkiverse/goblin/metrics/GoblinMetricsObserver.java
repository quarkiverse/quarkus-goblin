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
import io.quarkiverse.goblin.AssaultType;
import io.quarkus.runtime.StartupEvent;

/**
 * Optional Micrometer integration exposing the assault activity for Prometheus and other metric backends.
 * <p>
 * The following metrics are registered against the application's {@link MeterRegistry} when the
 * {@code quarkus-goblin-metrics} dependency is present:
 * <ul>
 * <li>{@code goblin.assaults.total} -- counter of every fired assault, tagged with its {@code type}, {@code source}
 * ({@code server}, {@code service}, {@code rest-client}, {@code webclient}, {@code database}, {@code messaging}, as
 * recorded by the engine) and {@code injected_value} (e.g. {@code HTTP 503}; {@value #NO_VALUE} for a latency, whose
 * value is in the timer, and when unknown);</li>
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
    public static final String TAG_INJECTED_VALUE = "injected_value";

    /**
     * The {@value #TAG_INJECTED_VALUE} tag of a latency assault, and of an assault whose value is unknown.
     */
    public static final String NO_VALUE = "-";

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
        String source = record.sourceTag();
        Counter.builder(TOTAL_METRIC)
                .tags(TAG_TYPE, record.type(), TAG_SOURCE, source, TAG_INJECTED_VALUE, injectedValueTag(record))
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
     * Returns the {@value #TAG_INJECTED_VALUE} tag of an assault. Every value but a latency is bounded by the
     * configuration (a status code, an exception class and message, a body mode, a header rule); a latency is drawn per
     * request, so as a tag it would create one series per delay: it is reported as {@value #NO_VALUE}, and its value is
     * in the {@value #LATENCY_METRIC} timer.
     *
     * @param record the assault record
     * @return the tag value, never empty
     */
    static String injectedValueTag(AssaultEngine.AssaultRecord record) {
        String value = record.injectedValue();
        if (record.latencyMs() > 0 || record.assaultType() == AssaultType.LATENCY || value == null || value.isEmpty()) {
            return NO_VALUE;
        }
        return value;
    }
}
