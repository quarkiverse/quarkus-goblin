package io.quarkiverse.goblin.assault;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Chaos assault that simulates a failing downstream dependency by aborting the request with a fixed {@code 503}
 * response. Designed to exercise {@code @Fallback} and {@code @Retry} on outbound service calls.
 */
@ApplicationScoped
public class DependencyDegradationAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(DependencyDegradationAssault.class);

    /**
     * {@inheritDoc}
     *
     * @return the {@link AssaultType#DEPENDENCY_DEGRADATION} assault type
     */
    @Override
    public AssaultType type() {
        return AssaultType.DEPENDENCY_DEGRADATION;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 40}, the last assault in the execution chain
     */
    @Override
    public int order() {
        return 40;
    }

    /**
     * {@inheritDoc}
     *
     * @return whether the dependency degradation assault is enabled in the configuration
     */
    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isDependencyDegradationEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "dependency-degradation"}, the label used in history and report
     */
    @Override
    public String recordLabel() {
        return "dependency-degradation";
    }

    /**
     * {@inheritDoc}
     * <p>
     * Aborts the request with a {@code 503} response and a fixed "Dependency unavailable" body.
     *
     * @return {@link AssaultOutcome#ABORTED}
     */
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