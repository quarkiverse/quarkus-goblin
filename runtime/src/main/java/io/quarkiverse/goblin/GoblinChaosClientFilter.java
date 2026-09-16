package io.quarkiverse.goblin;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.assault.ExceptionAssault;

/**
 * JAX-RS client filter that injects latency and exceptions on <em>outgoing</em> calls made with MicroProfile REST
 * Client or Quarkus REST Client Reactive.
 * <p>
 * The filter mirrors {@link GoblinChaosFilter} on the client side: it is registered globally (via {@code @Provider},
 * applied to every REST client in the application) and only acts on calls gated by {@link AssaultEngine#shouldAssaultClient()}.
 * Only latency and exception assaults apply to outbound calls -- HTTP status and dependency degradation are
 * server-side response manipulations and are never applied here.
 * <p>
 * The remote service is never reached nor modified: latency is applied via {@link Thread#sleep(long)} before the
 * request is dispatched, and exceptions are thrown before the request is sent. Every fired assault is recorded in the
 * assault history and reported like a server-side assault.
 */
@Provider
@ApplicationScoped
public class GoblinChaosClientFilter implements ClientRequestFilter {

    private static final Logger LOG = Logger.getLogger(GoblinChaosClientFilter.class);

    @Inject
    AssaultEngine engine;

    /**
     * Injects the configured client-side assaults, if any, into the outgoing call.
     * <p>
     * When the client latency assault is enabled a random delay within the configured latency range is applied before
     * the request is dispatched. When the client exception assault is enabled the configured exception is thrown before
     * the request is sent, so the remote service is never reached. Both assaults are recorded in the history.
     *
     * @param requestContext the outbound JAX-RS client request context
     * @throws IOException never thrown; declared for the {@link ClientRequestFilter} contract
     */
    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (!engine.isActive() || !engine.shouldAssaultClient()) {
            return;
        }

        MutableAssaultConfig config = engine.getMutableConfig();
        String methodName = describeClientCall(requestContext);

        if (config.isClientLatencyEnabled()) {
            long min = config.getLatencyMinMs();
            long max = config.getLatencyMaxMs();
            long delay = ThreadLocalRandom.current().nextLong(min, max + 1);
            LOG.debugf("Goblin: injecting client latency on %s", methodName);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            engine.recordAssault(methodName, "latency", delay);
        }

        if (config.isClientExceptionEnabled()) {
            LOG.debugf("Goblin: injecting client exception on %s", methodName);
            engine.recordAssault(methodName, "exception");
            throw ExceptionAssault.createException(config);
        }
    }

    /**
     * Produces the history identifier for an outbound call, e.g. {@code "REST-Client GET http://localhost:8081/api/hello"}.
     *
     * @param requestContext the outbound JAX-RS client request context
     * @return a human-readable identifier combining the HTTP method and the request URI
     */
    private static String describeClientCall(ClientRequestContext requestContext) {
        URI uri = requestContext.getUri();
        return "REST-Client " + requestContext.getMethod() + " " + (uri != null ? uri : "<unknown>");
    }
}