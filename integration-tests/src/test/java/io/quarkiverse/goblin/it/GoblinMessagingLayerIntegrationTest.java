package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.BooleanSupplier;

import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;

/**
 * Exercises the MESSAGING layer (issue #54, phase 3): each consumed message is its own pseudo-request, faulted at the
 * {@code @Incoming} consumer when MESSAGING wins, or deeper (DATABASE, SERVICE) inside the consumer otherwise.
 */
@QuarkusTest
public class GoblinMessagingLayerIntegrationTest {

    private static final String ORDERS_CONSUMER = "Messaging io.quarkiverse.goblin.it.SampleConsumer.consume";

    @Inject
    AssaultEngine engine;

    @Inject
    SampleConsumer consumer;

    @Inject
    @Any
    InMemoryConnector connector;

    @BeforeEach
    void resetState() {
        engine.setActive(true);
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.resetToDefaults();
        cfg.setLatencyEnabled(false);
        cfg.setLatencyRange(100, 150);
        cfg.setLayers(List.of(ChaosLayer.MESSAGING));
        cfg.setTargetLevel(100);
        engine.clearHistory();
        consumer.reset();
    }

    @AfterEach
    void restoreDefaults() {
        engine.getMutableConfig().resetToDefaults();
    }

    @Test
    public void messagingLayerIsAvailableWithQuarkusMessaging() {
        assertTrue(engine.isLayerAvailable(ChaosLayer.MESSAGING));
    }

    @Test
    public void messagingExceptionNacksTheMessageBeforeTheConsumerRuns() {
        engine.getMutableConfig().setExceptionEnabled(true);

        connector.source("orders").send("order-1");

        awaitCondition(() -> engine.getHistory().stream().anyMatch(record -> ORDERS_CONSUMER.equals(record.method())));
        assertEquals("exception", engine.getHistory().get(0).type());
        assertTrue(consumer.processed().isEmpty(), "the consumer body must never run, got: " + consumer.processed());
    }

    @Test
    public void messagingLatencyDelaysTheConsumer() {
        engine.getMutableConfig().setLatencyEnabled(true);

        connector.source("orders").send("order-2");

        awaitCondition(() -> consumer.processed().contains("order-2"));
        AssaultEngine.AssaultRecord latency = engine.getHistory().stream()
                .filter(record -> ORDERS_CONSUMER.equals(record.method()) && "latency".equals(record.type()))
                .findFirst()
                .orElseThrow();
        assertTrue(latency.latencyMs() >= 100 && latency.latencyMs() <= 150, "got: " + latency.latencyMs());
    }

    @Test
    public void databaseFaultSurfacesInsideMessageProcessing() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setLayers(List.of(ChaosLayer.DATABASE, ChaosLayer.MESSAGING));

        connector.source("audits").send("audit-1");

        awaitCondition(() -> engine.getHistory().stream()
                .anyMatch(record -> "Database <default> connection".equals(record.method())));
        assertTrue(engine.getHistory().stream().noneMatch(record -> record.method().startsWith("Messaging ")),
                "the deeper DATABASE layer shadows MESSAGING, got: " + engine.getHistory());
        assertFalse(consumer.processed().contains("audit-1"), "the audit must have failed on the database");
    }

    @Test
    public void messagingLayerInertWhenNotArmed() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setLayers(List.of(ChaosLayer.HTTP_IN));

        connector.source("orders").send("order-3");

        awaitCondition(() -> consumer.processed().contains("order-3"));
        assertTrue(engine.getHistory().isEmpty(), "got: " + engine.getHistory());
    }

    @Test
    public void staleDecisionOfAFailedHttpRequestNeverLeaksIntoAConsumer() {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        cfg.setExceptionEnabled(true);
        cfg.setLayers(List.of(ChaosLayer.SERVICE));
        // the service exception escapes the resource: the JAX-RS response filter never clears the worker thread
        for (int i = 0; i < 4; i++) {
            io.restassured.RestAssured.given().get("/api/service/hello").then().statusCode(500);
        }
        cfg.setLayers(List.of(ChaosLayer.HTTP_IN));
        engine.clearHistory();

        connector.source("orders").send("order-4");

        awaitCondition(() -> consumer.processed().contains("order-4"));
        assertTrue(engine.getHistory().isEmpty(),
                "the consumer must not inherit the SERVICE decision of an earlier request, got: " + engine.getHistory());
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("condition not met within 5 s");
    }
}
