package io.quarkiverse.goblin.assault;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Latency then exception injection shared by the layer hooks that sit below the HTTP boundary (service interceptor,
 * database pool interceptor, messaging interceptor), so every layer applies, records and logs its faults the same way.
 */
public final class LayerFaults {

    private static final Logger LOG = Logger.getLogger(LayerFaults.class);

    static final String RECORD_LABEL_LATENCY = "latency";
    static final String RECORD_LABEL_EXCEPTION = "exception";

    private LayerFaults() {
    }

    /**
     * Applies the enabled latency assault, then the enabled exception assault, to the given target.
     * <p>
     * The latency is recorded once the delay has elapsed; when it is interrupted (e.g. by {@code @Timeout}) the delay
     * actually endured is recorded instead of the drawn one. A latency skipped on an event-loop thread is not an assault
     * and is not recorded.
     *
     * @param engine the engine recording the assaults
     * @param cfg the active configuration
     * @param source the source the assaults are recorded under
     * @param target the history identifier of the assaulted target
     * @throws InterruptedException when the latency assault is interrupted
     * @throws RuntimeException the configured exception when the exception assault is enabled
     */
    public static void inject(AssaultEngine engine, MutableAssaultConfig cfg, AssaultSource source, String target)
            throws InterruptedException {
        if (cfg.isLatencyEnabled()) {
            long latency = LatencySupport.drawDelay(cfg);
            LOG.debugf("Goblin: %s layer (level %d) injecting %d ms latency into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), latency, target);
            long start = System.nanoTime();
            try {
                if (LatencySupport.sleep(latency, target)) {
                    engine.recordAssault(source, target, RECORD_LABEL_LATENCY, latency);
                }
            } catch (InterruptedException e) {
                // cut short (typically by @Timeout): record the delay actually endured, not the one drawn
                long endured = Math.min(latency, (System.nanoTime() - start) / 1_000_000);
                engine.recordAssault(source, target, RECORD_LABEL_LATENCY, endured);
                throw e;
            }
        }

        if (cfg.isExceptionEnabled()) {
            LOG.debugf("Goblin: %s layer (level %d) throwing %s into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), cfg.getExceptionType(), target);
            engine.recordAssault(source, target, RECORD_LABEL_EXCEPTION);
            throw ExceptionAssault.createException(cfg);
        }
    }

    /**
     * Decides whether the armed layer fires on this hook invocation: the first invocation of the request uses the
     * per-request decision, every further one draws the target level again (a {@code @Retry} attempt, a second
     * connection acquisition...).
     *
     * @param engine the engine drawing the target level
     * @return {@code true} when the fault must be injected now
     */
    public static boolean shouldFire(AssaultEngine engine) {
        return !ChaosRequestContext.markFired() || engine.drawLevelGate();
    }
}
