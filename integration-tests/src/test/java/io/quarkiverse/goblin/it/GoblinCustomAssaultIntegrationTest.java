package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * Custom {@code Assault} beans of the application, driven and observed through the public API only: they are
 * discovered, applied by the filter in {@code order()} sequence, and recorded by {@code AssaultContext.record(this)}
 * with their own label and type.
 */
@QuarkusTest
class GoblinCustomAssaultIntegrationTest {

    @Inject
    AssaultEngine engine;

    @Inject
    TeapotAssault teapot;

    @Inject
    MarkerAssault marker;

    @BeforeEach
    void reset() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        // the per-request gate opens only when a built-in toggle is on
        cfg.setLatencyEnabled(true);
        cfg.setLatencyRange(10, 20);
        engine.clearHistory();
        engine.resetCounters();
    }

    @AfterEach
    void disarm() {
        teapot.arm(false);
        marker.arm(false);
        engine.getMutableConfig().setLatencyEnabled(false);
    }

    @Test
    void anAbortingCustomAssaultIsAppliedAndRecordedWithItsLabelAndType() {
        teapot.arm(true);

        // the teapot (order 0) aborts: the built-in latency (order 10) never runs
        RestAssured.given().get("/api/hello").then().statusCode(418);

        List<AssaultEngine.AssaultRecord> history = engine.getHistory();
        assertEquals(1, history.size(), history.toString());
        AssaultEngine.AssaultRecord record = history.get(0);
        assertEquals(TeapotAssault.LABEL, record.type());
        assertEquals(AssaultType.HTTP_STATUS, record.assaultType());
        assertEquals(AssaultSource.SERVER, record.source());
        assertTrue(record.method().endsWith("SampleResource.hello"), record.method());
        assertEquals(1L, engine.getAssaultCounts().get(TeapotAssault.LABEL));
    }

    @Test
    void aCustomAssaultReturningContinueLetsTheBuiltInAssaultsRunAfterIt() {
        marker.arm(true);

        RestAssured.given().get("/api/hello").then().statusCode(200);

        List<AssaultEngine.AssaultRecord> history = engine.getHistory();
        assertEquals(List.of(MarkerAssault.LABEL, "latency"), history.stream().map(AssaultEngine.AssaultRecord::type)
                .toList(), history.toString());
        assertEquals(AssaultType.RESPONSE_HEADER, history.get(0).assaultType());
        assertEquals(AssaultType.LATENCY, history.get(1).assaultType());
    }

    @Test
    void aCustomAssaultWithoutScopeAnnotationIsApplicationScoped() {
        assertEquals(ApplicationScoped.class, Arc.container().instance(MarkerAssault.class).getBean().getScope());
    }
}
