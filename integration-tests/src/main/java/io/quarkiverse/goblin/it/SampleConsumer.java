package io.quarkiverse.goblin.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import io.smallrye.reactive.messaging.annotations.Blocking;

/**
 * Message consumers of the integration-tests app, fed through the in-memory connector. {@code orders} only records the
 * payload; {@code audits} also hits the database through {@link SampleService}, so a DATABASE-layer fault can surface inside
 * message processing.
 */
@ApplicationScoped
public class SampleConsumer {

    private static final Logger LOG = Logger.getLogger(SampleConsumer.class);

    @Inject
    SampleService service;

    private final List<String> processed = new CopyOnWriteArrayList<>();

    @Incoming("orders")
    @Blocking
    public void consume(String order) {
        LOG.debugf("SampleConsumer.consume(%s) processing the order", order);
        processed.add(order);
    }

    @Incoming("audits")
    @Blocking
    public void audit(String entry) {
        LOG.debugf("SampleConsumer.audit(%s) writing the audit entry", entry);
        service.databasePing();
        processed.add(entry);
    }

    public List<String> processed() {
        return List.copyOf(processed);
    }

    public void reset() {
        processed.clear();
    }
}
