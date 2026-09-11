package io.quarkiverse.goblin.assault;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

@ApplicationScoped
public class DependencyDegradationAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(DependencyDegradationAssault.class);

    @Override
    public AssaultType type() {
        return AssaultType.DEPENDENCY_DEGRADATION;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isDependencyDegradationEnabled();
    }

    @Override
    public String recordLabel() {
        return "dependency-degradation";
    }

    @Override
    public AssaultOutcome apply(AssaultContext context) {
        LOG.debugf("Goblin: simulating dependency degradation on %s", context.getMethodName());
        context.getEngine().recordAssault(context.getMethodName(), recordLabel());
        context.getRequestContext().abortWith(Response.status(503)
                .entity("Dependency unavailable (Goblin chaos)")
                .build());
        return AssaultOutcome.ABORTED;
    }
}