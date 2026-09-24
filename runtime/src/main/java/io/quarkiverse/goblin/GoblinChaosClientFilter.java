package io.quarkiverse.goblin;

import java.io.IOException;
import java.net.URI;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.assault.ExceptionAssault;
import io.quarkiverse.goblin.assault.LatencySupport;

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
 * request is dispatched (skipped on a Vert.x event-loop thread, which must never block), and exceptions are thrown before the
 * request is sent. Every fired assault is recorded in the
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

        MutableAssaultConfig config = engine.configSnapshot();
        String methodName = describeClientCall(requestContext);

        if (config.isClientLatencyEnabled()) {
            long delay = LatencySupport.drawDelay(config);
            LOG.debugf("Goblin: injecting client latency on %s", methodName);
            boolean applied;
            try {
                applied = LatencySupport.sleep(delay, methodName);
            } catch (InterruptedException e) {
                // interrupt flag restored by LatencySupport: the client call observes it
                applied = true;
            }
            if (applied) {
                engine.recordAssault(AssaultSource.REST_CLIENT, methodName, "latency", delay);
            }
        }

        if (config.isClientExceptionEnabled()) {
            LOG.debugf("Goblin: injecting client exception on %s", methodName);
            engine.recordAssault(AssaultSource.REST_CLIENT, methodName, "exception");
            throw ExceptionAssault.createException(config);
        }
    }

    /**
     * Produces the history identifier for an outbound call, e.g. {@code "REST-Client GET http://localhost:8081/api/hello"}.
     * The query string, fragment and user info are stripped: they routinely carry tokens or credentials that must not
     * end up in the history, the traces or the logs.
     *
     * @param requestContext the outbound JAX-RS client request context
     * @return a human-readable identifier combining the HTTP method and the request URI
     */
    private static String describeClientCall(ClientRequestContext requestContext) {
        URI uri = requestContext.getUri();
        return "REST-Client " + requestContext.getMethod() + " " + sanitize(uri);
    }

    /**
     * Reduces a URI to {@code scheme://host[:port]/path}, dropping user info, query and fragment.
     *
     * @param uri the request URI, possibly {@code null}
     * @return the sanitised URI, or {@code "<unknown>"} when absent
     */
    static String sanitize(URI uri) {
        if (uri == null) {
            return "<unknown>";
        }
        if (uri.getHost() == null) {
            String path = uri.getRawPath();
            return path != null ? path : "<unknown>";
        }
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        sb.append(uri.getHost());
        if (uri.getPort() >= 0) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        return sb.toString();
    }
}