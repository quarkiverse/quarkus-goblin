package io.quarkiverse.goblin.service;

import java.util.concurrent.ThreadLocalRandom;

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

/**
 * CDI interceptor injecting latency and exception assaults on application beans at the service layer, bypassing the HTTP
 * layer entirely.
 * <p>
 * Only fires when the {@link io.quarkiverse.goblin.ChaosLayer#SERVICE} layer was selected as the armed layer for the
 * current request by {@link AssaultEngine#resolveAssaultLayer()}; the decision itself is made once per request by the
 * inbound filter and carried through {@link ChaosRequestContext}, so this interceptor stays cheap on every other call.
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
     * this request and the relevant toggle is armed. Passes through immediately otherwise.
     *
     * @param context the intercepted invocation
     * @return the invocation result, possibly after a latency assault
     * @throws RuntimeException the configured exception when the exception assault fires
     * @throws InterruptedException when the latency assault is interrupted
     */
    @AroundInvoke
    Object aroundInvoke(InvocationContext context) throws Exception {
        if (!ChaosRequestContext.isServiceArmed() || engine.getMutableConfig() == null) {
            return context.proceed();
        }
        MutableAssaultConfig cfg = engine.getMutableConfig();
        String methodName = describe(context);

        if (cfg.isLatencyEnabled()) {
            long min = cfg.getLatencyMinMs();
            long max = cfg.getLatencyMaxMs();
            long latency = ThreadLocalRandom.current().nextLong(min, max == Long.MAX_VALUE ? max : max + 1);
            LOG.debugf("Goblin: service layer %s (level %d) injecting %d ms latency (range %d-%d ms) into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), latency, min, max, methodName);
            engine.recordAssault(methodName, RECORD_LABEL_LATENCY, latency);
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        if (cfg.isExceptionEnabled()) {
            LOG.debugf("Goblin: service layer %s (level %d) throwing %s into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), cfg.getExceptionType(), methodName);
            engine.recordAssault(methodName, RECORD_LABEL_EXCEPTION);
            throw ExceptionAssault.createException(cfg);
        }

        return context.proceed();
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