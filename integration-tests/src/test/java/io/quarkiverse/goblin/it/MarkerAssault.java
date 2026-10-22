package io.quarkiverse.goblin.it;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.assault.Assault;
import io.quarkiverse.goblin.assault.AssaultContext;
import io.quarkiverse.goblin.assault.AssaultOutcome;

/**
 * A custom assault that only records itself and lets the chain go on ({@link AssaultOutcome#CONTINUE}), so the built-in
 * assaults ordered after it still run. Deliberately declared without a scope annotation: Goblin registers it as
 * {@code @ApplicationScoped}. Off unless a test arms it.
 */
public class MarkerAssault implements Assault {

    public static final String LABEL = "custom-marker";

    private volatile boolean armed;

    public void arm(boolean armed) {
        this.armed = armed;
    }

    @Override
    public AssaultType type() {
        return AssaultType.RESPONSE_HEADER;
    }

    @Override
    public boolean isEnabled(AssaultConfig config) {
        return armed;
    }

    @Override
    public String recordLabel() {
        return LABEL;
    }

    @Override
    public int order() {
        // before the built-in latency (10)
        return 1;
    }

    @Override
    public AssaultOutcome apply(AssaultContext context) {
        context.record(this);
        return AssaultOutcome.CONTINUE;
    }
}
