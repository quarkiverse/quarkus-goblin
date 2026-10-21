package io.quarkiverse.goblin.internal.assault;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.assault.Assault;
import io.quarkiverse.goblin.assault.AssaultContext;
import io.quarkiverse.goblin.assault.AssaultOutcome;

/**
 * Chaos assault that aborts the request with a configurable HTTP status code and message.
 * <p>
 * The status code and response body are read from the {@link AssaultConfig}; the endpoint method is never
 * executed.
 */
@ApplicationScoped
public class HttpStatusAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(HttpStatusAssault.class);

    /**
     * {@inheritDoc}
     *
     * @return the {@link AssaultType#HTTP_STATUS} assault type
     */
    @Override
    public AssaultType type() {
        return AssaultType.HTTP_STATUS;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 30}, running after latency and exception
     */
    @Override
    public int order() {
        return 30;
    }

    /**
     * {@inheritDoc}
     *
     * @return whether the HTTP status assault is enabled in the configuration
     */
    @Override
    public boolean isEnabled(AssaultConfig config) {
        return config.isHttpStatusEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "http-status"}, the label used in history and report
     */
    @Override
    public String recordLabel() {
        return "http-status";
    }

    /**
     * {@inheritDoc}
     * <p>
     * Aborts the request with the configured status code and message body.
     *
     * @return {@link AssaultOutcome#ABORTED}
     */
    @Override
    public AssaultOutcome apply(AssaultContext context) {
        AssaultConfig config = context.getConfig();
        LOG.debugf("Goblin: forcing HTTP %d on %s", config.getHttpStatusCode(), context.getMethodName());
        context.record(this);
        context.getRequestContext().abortWith(AbortResponses.status(config.getHttpStatusCode(),
                config.getHttpStatusMessage(), context.getResourceMethod(), context.getResourceClass()));
        return AssaultOutcome.ABORTED;
    }
}
