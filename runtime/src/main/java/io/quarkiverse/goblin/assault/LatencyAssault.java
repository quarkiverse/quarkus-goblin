package io.quarkiverse.goblin.assault;

import java.util.concurrent.ThreadLocalRandom;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

@ApplicationScoped
public class LatencyAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(LatencyAssault.class);

    @Override
    public AssaultType type() {
        return AssaultType.LATENCY;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isLatencyEnabled();
    }

    @Override
    public String recordLabel() {
        return "latency";
    }

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