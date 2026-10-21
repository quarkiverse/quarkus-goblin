package io.quarkiverse.goblin.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;

class GoblinMetricsObserverTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AssaultEngineImpl engine = new AssaultEngineImpl();
    private final GoblinMetricsObserver observer = new GoblinMetricsObserver(registry, engine);

    @Test
    void activeGaugeFollowsEngineState() {
        engine.setActive(true);
        assertEquals(1, registry.get(GoblinMetricsObserver.ACTIVE_METRIC).gauge().value());

        engine.setActive(false);
        assertEquals(0, registry.get(GoblinMetricsObserver.ACTIVE_METRIC).gauge().value());
    }

    @Test
    void serverLatencyAssaultIsCountedAndTimed() {
        observer.onAssault(record("SampleResource.hello", "latency", 250));

        assertEquals(1, totalCount("latency", "server"));
        assertNull(totalCounter("latency", "rest-client"));
        assertNull(totalCounter("latency", "webclient"));

        Timer timer = registry.find(GoblinMetricsObserver.LATENCY_METRIC)
                .tag("source", "server").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(0.25, timer.totalTime(TimeUnit.SECONDS), 0.001);
    }

    @Test
    void nonLatencyAssaultIsCountedButNotTimed() {
        observer.onAssault(record("SampleResource.hello", "http-status", 0));

        assertEquals(1, totalCount("http-status", "server"));
        assertNull(registry.find(GoblinMetricsObserver.LATENCY_METRIC).tag("source", "server").timer());
    }

    @Test
    void restClientSourceIsDetected() {
        observer.onAssault(
                record(AssaultSource.REST_CLIENT, "REST-Client GET http://localhost:8081/api/hello", "latency", 100));

        assertEquals(1, totalCount("latency", "rest-client"));
        Timer timer = registry.find(GoblinMetricsObserver.LATENCY_METRIC)
                .tag("source", "rest-client").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void webClientSourceIsDetected() {
        observer.onAssault(record(AssaultSource.WEBCLIENT, "WebClient GET http://localhost:8081/api/hello", "latency", 100));

        assertEquals(1, totalCount("latency", "webclient"));
    }

    @Test
    void unknownMethodFallsBackToServer() {
        observer.onAssault(record(null, "exception", 0));
        observer.onAssault(record("", "exception", 0));

        assertEquals(2, totalCount("exception", "server"));
    }

    @Test
    void sourceTagComesFromTheRecord() {
        observer.onAssault(record(AssaultSource.SERVICE, "com.acme.Service.call", "exception", 0));
        observer.onAssault(record(AssaultSource.DATABASE, "Database <default> connection", "exception", 0));
        observer.onAssault(record(AssaultSource.MESSAGING, "Messaging com.acme.Consumer.consume", "exception", 0));

        assertEquals(1, totalCount("exception", "service"));
        assertEquals(1, totalCount("exception", "database"));
        assertEquals(1, totalCount("exception", "messaging"));
    }

    @Test
    void injectedValueTagIsBoundedByTheConfiguration() {
        observer.onAssault(new AssaultEngine.AssaultRecord("SampleResource.hello", "http-status",
                System.currentTimeMillis(), 0, "snapshot", AssaultSource.SERVER, AssaultType.HTTP_STATUS, "HTTP 429"));
        observer.onAssault(new AssaultEngine.AssaultRecord("SampleResource.hello", "latency",
                System.currentTimeMillis(), 842, "snapshot", AssaultSource.SERVER, AssaultType.LATENCY, "842ms"));
        observer.onAssault(new AssaultEngine.AssaultRecord("SampleResource.hello", "latency",
                System.currentTimeMillis(), 843, "snapshot", AssaultSource.SERVER, AssaultType.LATENCY, "843ms"));

        assertEquals(1, registry.find(GoblinMetricsObserver.TOTAL_METRIC).tag("type", "http-status")
                .tag("injected_value", "HTTP 429").counter().count());
        assertEquals(2, registry.find(GoblinMetricsObserver.TOTAL_METRIC).tag("type", "latency")
                .tag("injected_value", "-").counter().count(), "a latency is a single series, its value is in the timer");
        assertEquals(2, registry.find(GoblinMetricsObserver.TOTAL_METRIC).counters().size());
    }

    @Test
    void anUnknownInjectedValueIsTaggedWithADash() {
        observer.onAssault(record("SampleResource.hello", "exception", 0));

        assertNotNull(registry.find(GoblinMetricsObserver.TOTAL_METRIC).tag("injected_value", "-").counter());
    }

    private AssaultEngine.AssaultRecord record(String method, String type, long latencyMs) {
        return new AssaultEngine.AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, "snapshot",
                AssaultSource.SERVER, assaultType(type), "");
    }

    private AssaultEngine.AssaultRecord record(AssaultSource source, String method, String type, long latencyMs) {
        return new AssaultEngine.AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, "snapshot", source,
                assaultType(type), "");
    }

    private double totalCount(String type, String source) {
        var counter = totalCounter(type, source);
        assertNotNull(counter, "expected a goblin.assaults.total counter for " + source + "/" + type);
        return counter.count();
    }

    private io.micrometer.core.instrument.Counter totalCounter(String type, String source) {
        return registry.find(GoblinMetricsObserver.TOTAL_METRIC)
                .tag("type", type)
                .tag("source", source)
                .counter();
    }

    /** The assault type of a built-in label without detail, e.g. {@code "http-status"}. */
    private static AssaultType assaultType(String label) {
        return AssaultType.valueOf(label.toUpperCase().replace('-', '_'));
    }
}
