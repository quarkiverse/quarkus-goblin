package io.quarkiverse.goblin.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.goblin.AssaultEngine;

class GoblinMetricsObserverTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AssaultEngine engine = new AssaultEngine();
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
        observer.onAssault(record("REST-Client GET http://localhost:8081/api/hello", "latency", 100));

        assertEquals(1, totalCount("latency", "rest-client"));
        Timer timer = registry.find(GoblinMetricsObserver.LATENCY_METRIC)
                .tag("source", "rest-client").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void webClientSourceIsDetected() {
        observer.onAssault(record("WebClient GET http://localhost:8081/api/hello", "latency", 100));

        assertEquals(1, totalCount("latency", "webclient"));
    }

    @Test
    void unknownMethodFallsBackToServer() {
        observer.onAssault(record(null, "exception", 0));
        observer.onAssault(record("", "exception", 0));

        assertEquals(2, totalCount("exception", "server"));
    }

    @Test
    void sourceDetectionIsUnitTestable() {
        assertEquals("server", GoblinMetricsObserver.sourceOf("SampleResource.hello"));
        assertEquals("server", GoblinMetricsObserver.sourceOf(""));
        assertEquals("rest-client", GoblinMetricsObserver.sourceOf("REST-Client GET http://x"));
        assertEquals("webclient", GoblinMetricsObserver.sourceOf("WebClient GET http://x"));
    }

    private AssaultEngine.AssaultRecord record(String method, String type, long latencyMs) {
        return new AssaultEngine.AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, "snapshot");
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
}