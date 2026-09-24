package io.quarkiverse.goblin.messaging;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.assault.LayerFaults;

/**
 * CDI interceptor backing the {@link ChaosLayer#MESSAGING} layer, bound to the application's {@code @Incoming}
 * consumer methods.
 * <p>
 * A consumed message has no inbound HTTP request, so this interceptor is the entry point of its own pseudo-request:
 * it resolves the armed layer among {@link AssaultEngine#MESSAGE_LAYERS} ({@code DATABASE}, {@code MESSAGING},
 * {@code SERVICE}), carries the decision through {@link ChaosRequestContext} for the whole consumer invocation -- so a
 * deeper database or service fault fires inside the consumer exactly as it would inside a REST call -- and injects the
 * latency / exception itself when {@code MESSAGING} wins. The context is cleared when the consumer returns.
 * <p>
 * <strong>Placement contract:</strong> {@code @Priority(4005)} sits <em>outside</em> the MicroProfile Fault Tolerance
 * interceptor ({@code 4010}): a messaging-layer fault stands for the delivery of the message failing, so it is handled
 * by the messaging failure strategy (nack, dead-letter queue, ...) rather than by a {@code @Retry} on the consumer. A
 * service-layer fault on the same consumer method still runs inside Fault Tolerance ({@code 4100}).
 * <p>
 * Only the synchronous part of the consumer is covered: for a consumer returning {@code Uni} / {@code CompletionStage}
 * the context is cleared when the method returns, before the asynchronous processing. Latency needs a blocking consumer
 * ({@code @Blocking}, {@code @RunOnVirtualThread}): on an event-loop thread it is skipped rather than blocking the loop.
 */
@Interceptor
@GoblinMessagingAssault
@Priority(4005)
public class GoblinMessagingInterceptor {

    private static final Logger LOG = Logger.getLogger(GoblinMessagingInterceptor.class);

    @Inject
    AssaultEngine engine;

    /**
     * Resolves the armed layer for the consumed message, injects the messaging fault when that layer wins, then runs
     * the consumer with the decision in scope.
     *
     * @param context the intercepted consumer invocation
     * @return the consumer result
     * @throws Exception the configured exception when the messaging exception assault fires, or the consumer failure
     */
    @AroundInvoke
    Object aroundInvoke(InvocationContext context) throws Exception {
        MutableAssaultConfig cfg = engine.configSnapshot();
        if (cfg == null || !engine.isActive() || ChaosRequestContext.assaultLayer() != null) {
            // inactive, or invoked inside an already resolved pseudo-request (e.g. an in-memory channel fed from a
            // REST call): the enclosing decision stays in charge
            return context.proceed();
        }
        ChaosLayer layer = engine.resolveAssaultLayer(AssaultEngine.MESSAGE_LAYERS);
        if (layer == null) {
            return context.proceed();
        }
        String target = describe(context);
        LOG.debugf("Goblin: message consumed by %s resolved to %s layer (level %d)", target, layer, cfg.getTargetLevel());
        ChaosRequestContext.setAssaultLayer(layer);
        try {
            if (layer == ChaosLayer.MESSAGING) {
                ChaosRequestContext.markFired();
                LayerFaults.inject(engine, cfg, AssaultSource.MESSAGING, target);
            }
            return context.proceed();
        } finally {
            ChaosRequestContext.clear();
        }
    }

    /**
     * Builds the history identifier of a consumer, e.g. {@code "Messaging com.acme.OrderConsumer.consume"}.
     *
     * @param context the intercepted invocation
     * @return the history identifier
     */
    private static String describe(InvocationContext context) {
        return "Messaging " + context.getMethod().getDeclaringClass().getName() + "." + context.getMethod().getName();
    }
}
