package io.quarkiverse.goblin.assault;

import java.util.concurrent.ThreadLocalRandom;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Chaos assault that injects a random delay before the request reaches the endpoint.
 * <p>
 * The delay is drawn uniformly between the configured minimum and maximum milliseconds, inclusive, and applied via
 * {@link Thread#sleep(long)}. The applied value is recorded in the assault history.
 */
@ApplicationScoped
public class LatencyAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(LatencyAssault.class);

    /**
     * {@inheritDoc}
     *
     * @return the {@link AssaultType#LATENCY} assault type
     */
    @Override
    public AssaultType type() {
        return AssaultType.LATENCY;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 10}, making latency the first assault applied to eligible requests
     */
    @Override
    public int order() {
        return 10;
    }

    /**
     * {@inheritDoc}
     *
     * @return whether the latency assault is enabled in the configuration
     */
    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isLatencyEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "latency"}, the label used in history and report
     */
    @Override
    public String recordLabel() {
        return "latency";
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sleeps for a random duration within the configured latency range, records the actual applied delay, and lets the
     * execution chain proceed.
     *
     * @return {@link AssaultOutcome#CONTINUE}
     */
    @Override
    public AssaultOutcome apply(AssaultContext context) {
        MutableAssaultConfig config = context.getConfig();
        LOG.debugf("Goblin: injecting latency on %s", context.getMethodName());
        long min = config.getLatencyMinMs();
        long max = config.getLatencyMaxMs();
        long delay = ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        context.getEngine().recordAssault(context.getMethodName(), recordLabel(), delay);
        return AssaultOutcome.CONTINUE;
    }
}