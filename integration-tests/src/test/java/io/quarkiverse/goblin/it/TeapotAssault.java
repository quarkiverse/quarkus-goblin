package io.quarkiverse.goblin.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.assault.Assault;
import io.quarkiverse.goblin.assault.AssaultContext;
import io.quarkiverse.goblin.assault.AssaultOutcome;

/**
 * A custom assault written against the public SPI only, as an application would: answers 418 and records itself with
 * {@link AssaultContext#record(Assault)}. Off unless a test arms it, so the other suites never see it.
 */
@ApplicationScoped
public class TeapotAssault implements Assault {

    public static final String LABEL = "custom-teapot";

    private volatile boolean armed;

    public void arm(boolean armed) {
        this.armed = armed;
    }

    @Override
    public AssaultType type() {
        return AssaultType.HTTP_STATUS;
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
        // before the built-in latency (10): the abort below leaves no room for another assault
        return 0;
    }

    @Override
    public AssaultOutcome apply(AssaultContext context) {
        context.record(this);
        context.getRequestContext().abortWith(Response.status(418).entity("I'm a teapot (Goblin custom assault)").build());
        return AssaultOutcome.ABORTED;
    }
}
