package io.quarkiverse.goblin.service;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.assault.ExceptionAssault;
import io.quarkiverse.goblin.assault.LatencySupport;

/**
 * CDI interceptor injecting latency and exception assaults on application beans at the service layer, bypassing the HTTP
 * layer entirely.
 * <p>
 * Only fires when the {@link io.quarkiverse.goblin.ChaosLayer#SERVICE} layer was selected as the armed layer for the
 * current request by {@link AssaultEngine#resolveAssaultLayer()}; the decision itself is made once per request by the
 * inbound filter and carried through {@link ChaosRequestContext}, so this interceptor stays cheap on every other call.
 * <p>
 * Within an armed request, only the <em>outermost</em> intercepted call is assaulted: a bean calling another intercepted
 * bean never multiplies the fault. The first outermost call uses the per-request decision; every further outermost call
 * of the same request -- typically a {@code @Retry} attempt re-entering the method -- draws the target level again, so
 * at level 100 every attempt fails and at a lower level some attempts recover.
 * <p>
 * <strong>Placement contract:</strong> Jakarta Interceptors invoke lower priority values before higher ones, so an
 * interceptor with a higher {@code @Priority} sits closer to the bean. Quarkus SmallRye Fault Tolerance registers its
 * interceptor at {@code 4010}; this interceptor runs at {@code 4100}, i.e. <em>inside</em> the fault-tolerance
 * machinery: a thrown exception is therefore observed by {@code @Retry}, {@code @CircuitBreaker} and {@code @Fallback},
 * and the injected latency is covered by {@code @Timeout} and {@code @Bulkhead}. Changing this value relative to 4010
 * silently changes which resilience behaviour is being exercised.
 */
@Interceptor
@GoblinServiceAssault
@Priority(4100)
public class GoblinServiceInterceptor {

    private static final Logger LOG = Logger.getLogger(GoblinServiceInterceptor.class);

    private static final String RECORD_LABEL_LATENCY = "latency";
    private static final String RECORD_LABEL_EXCEPTION = "exception";

    @Inject
    AssaultEngine engine;

    /**
     * Applies the service-layer latency, then service-layer exception assaults when the service layer was selected for
     * this request, the call is the outermost intercepted one and the relevant toggle is armed. Passes through
     * immediately otherwise.
     *
     * @param context the intercepted invocation
     * @return the invocation result, possibly after a latency assault
     * @throws RuntimeException the configured exception when the exception assault fires
     * @throws InterruptedException when the latency assault is interrupted
     */
    @AroundInvoke
    Object aroundInvoke(InvocationContext context) throws Exception {
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (!ChaosRequestContext.isServiceArmed() || cfg == null || !engine.isActive()) {
            return context.proceed();
        }
        boolean outermost = ChaosRequestContext.enterService();
        try {
            if (outermost && (!ChaosRequestContext.markServiceFired() || engine.drawLevelGate())) {
                assault(context, cfg);
            }
            return context.proceed();
        } finally {
            ChaosRequestContext.exitService();
        }
    }

    private void assault(InvocationContext context, MutableAssaultConfig cfg) throws InterruptedException {
        String methodName = describe(context);

        if (cfg.isLatencyEnabled()) {
            long latency = LatencySupport.drawDelay(cfg);
            LOG.debugf("Goblin: service layer %s (level %d) injecting %d ms latency into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), latency, methodName);
            boolean applied = true;
            try {
                applied = LatencySupport.sleep(latency, methodName);
            } finally {
                // recorded once the delay has elapsed (or was interrupted, e.g. by @Timeout), like every other latency
                // hook; a latency skipped on an event-loop thread is not an assault and is not recorded
                if (applied) {
                    engine.recordAssault(methodName, RECORD_LABEL_LATENCY, latency);
                }
            }
        }

        if (cfg.isExceptionEnabled()) {
            LOG.debugf("Goblin: service layer %s (level %d) throwing %s into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), cfg.getExceptionType(), methodName);
            engine.recordAssault(methodName, RECORD_LABEL_EXCEPTION);
            throw ExceptionAssault.createException(cfg);
        }
    }

    /**
     * Builds a human-readable target descriptor ({@code ClassName.method}) for history and reports.
     *
     * @param context the intercepted invocation
     * @return the fully qualified method descriptor
     */
    private static String describe(InvocationContext context) {
        Class<?> declaringClass = context.getMethod().getDeclaringClass();
        return declaringClass.getName() + "." + context.getMethod().getName();
    }
}
