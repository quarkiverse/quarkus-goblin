package io.quarkiverse.goblin.assault;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultEngine;
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
     * The latency is recorded once the delay has elapsed (or was interrupted, e.g. by {@code @Timeout}); a latency
     * skipped on an event-loop thread is not an assault and is not recorded.
     *
     * @param engine the engine recording the assaults
     * @param cfg the active configuration
     * @param target the history identifier of the assaulted target
     * @throws InterruptedException when the latency assault is interrupted
     * @throws RuntimeException the configured exception when the exception assault is enabled
     */
    public static void inject(AssaultEngine engine, MutableAssaultConfig cfg, String target) throws InterruptedException {
        if (cfg.isLatencyEnabled()) {
            long latency = LatencySupport.drawDelay(cfg);
            LOG.debugf("Goblin: %s layer (level %d) injecting %d ms latency into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), latency, target);
            boolean applied = true;
            try {
                applied = LatencySupport.sleep(latency, target);
            } finally {
                if (applied) {
                    engine.recordAssault(target, RECORD_LABEL_LATENCY, latency);
                }
            }
        }

        if (cfg.isExceptionEnabled()) {
            LOG.debugf("Goblin: %s layer (level %d) throwing %s into %s",
                    ChaosRequestContext.assaultLayer(), cfg.getTargetLevel(), cfg.getExceptionType(), target);
            engine.recordAssault(target, RECORD_LABEL_EXCEPTION);
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
