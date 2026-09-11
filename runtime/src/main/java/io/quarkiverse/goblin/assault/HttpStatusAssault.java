package io.quarkiverse.goblin.assault;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

@ApplicationScoped
public class HttpStatusAssault implements Assault {

    private static final Logger LOG = Logger.getLogger(HttpStatusAssault.class);

    @Override
    public AssaultType type() {
        return AssaultType.HTTP_STATUS;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean isEnabled(MutableAssaultConfig config) {
        return config.isHttpStatusEnabled();
    }

    @Override
    public String recordLabel() {
        return "http-status";
    }

    @Override
    public AssaultOutcome apply(AssaultContext context) {
        MutableAssaultConfig config = context.getConfig();
        LOG.debugf("Goblin: forcing HTTP %d on %s", config.getHttpStatusCode(), context.getMethodName());
        context.getEngine().recordAssault(context.getMethodName(), recordLabel());
        context.getRequestContext().abortWith(Response.status(config.getHttpStatusCode())
                .entity(config.getHttpStatusMessage())
                .build());
        return AssaultOutcome.ABORTED;
    }
}