package io.quarkiverse.goblin;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import jakarta.enterprise.inject.spi.CDI;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.assault.ExceptionAssault;
import io.quarkiverse.goblin.assault.LatencySupport;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.client.impl.WebClientInternal;

/**
 * Extends the client-side chaos assaults (latency and exception) to outgoing calls made with Vert.x {@code WebClient}.
 * <p>
 * Unlike the MicroProfile REST Client path, which is intercepted globally through a registered
 * {@code ClientRequestFilter}, Vert.x 4.x exposes no public request-interceptor hook on {@code WebClient}. Goblin
 * therefore attaches its interceptor explicitly, using the same internal mechanism Vert.x itself relies on for its
 * {@code OAuth2WebClient}, {@code CachingWebClient} and {@code WebClientSession} decorators.
 * <p>
 * Usage is one line at the point where the application creates its {@code WebClient}:
 *
 * <pre>{@code
 * WebClient client = GoblinWebClient.enable(WebClient.create(vertx));
 * }</pre>
 *
 * The interceptor replays the exact same behavior as {@link GoblinChaosClientFilter}: when the client latency assault
 * is enabled a random delay within the configured latency range is applied before the request is dispatched, and when
 * the client exception assault is enabled the configured exception fails the request before it is sent -- the remote
 * service is never reached. Both assaults are recorded in the assault history with a {@code "WebClient"} prefix. A
 * latency assault is recorded once the delay has elapsed (like the server-side and REST Client latency paths), so the
 * recorded timestamp marks the moment the delay ended -- observers can therefore attribute the whole delay to the
 * operation by back-dating from it.
 * <p>
 * The delay is applied without blocking the event loop: when the request is dispatched from a Vert.x context a timer
 * is scheduled, and a blocking fallback is only used when no Vert.x context is available (plain worker thread).
 */
public final class GoblinWebClient {

    private static final Logger LOG = Logger.getLogger(GoblinWebClient.class);

    /**
     * WebClients the interceptor was already attached to, protecting {@link #enable(WebClient)} from stacking duplicate
     * interceptors on repeated calls. Keyed by identity (no {@code equals}/{@code hashCode} on Vert.x clients) and held
     * weakly, so closed clients -- and, in dev mode, clients of a previous application generation -- are collected.
     */
    private static final Set<WebClient> ENABLED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Engine override for unit tests only. The production path resolves the engine from CDI on every call, so a dev-mode
     * live reload (new application, new engine) is never served by a stale engine of the previous generation.
     */
    private static volatile AssaultEngine testEngine;

    private GoblinWebClient() {
    }

    /**
     * Attaches the Goblin client-side assault interceptor to the given {@code WebClient} and returns it.
     * <p>
     * Calling this method multiple times with the same instance only registers the interceptor once.
     *
     * @param webClient the {@code WebClient} to arm, never {@code null}
     * @return the same {@code WebClient}, ready to be chained
     * @throws IllegalArgumentException when the given instance is not backed by Vert.x's {@code WebClientInternal}
     *         implementation (e.g. a custom decorator that does not delegate {@code request(...)} to the base client)
     */
    public static WebClient enable(WebClient webClient) {
        Objects.requireNonNull(webClient, "webClient");
        if (!(webClient instanceof WebClientInternal internal)) {
            throw new IllegalArgumentException(
                    "Unsupported WebClient implementation: " + webClient.getClass().getName());
        }
        if (ENABLED.add(webClient)) {
            internal.addInterceptor(GoblinWebClient::intercept);
        }
        return webClient;
    }

    /**
     * Applies the configured client-side assaults to the outgoing call, once per dispatched request.
     *
     * @param context the Vert.x web client request context
     */
    private static void intercept(HttpContext<?> context) {
        if (context.phase() != ClientPhase.PREPARE_REQUEST) {
            context.next();
            return;
        }
        AssaultEngine engine = engine();
        if (!engine.shouldAssaultClient()) {
            context.next();
            return;
        }
        MutableAssaultConfig config = engine.configSnapshot();
        String methodName = describeCall(context);
        if (config.isClientLatencyEnabled()) {
            long delay = LatencySupport.drawDelay(config);
            LOG.debugf("Goblin: injecting WebClient latency (%s ms) on %s", delay, methodName);
            delayThen(context, delay, () -> {
                engine.recordAssault(AssaultSource.WEBCLIENT, methodName, "latency", delay);
                finishWithExceptionIfEnabled(context, config, methodName);
            });
            return;
        }
        if (config.isClientExceptionEnabled()) {
            failWithException(context, config, methodName);
            return;
        }
        context.next();
    }

    /**
     * Applies the client exception assault after the latency delay when both assaults are enabled.
     *
     * @param context the Vert.x web client request context
     * @param config the active configuration
     * @param methodName the history identifier of the outgoing call
     */
    private static void finishWithExceptionIfEnabled(HttpContext<?> context, MutableAssaultConfig config, String methodName) {
        if (config.isClientExceptionEnabled()) {
            failWithException(context, config, methodName);
        } else {
            context.next();
        }
    }

    /**
     * Fails the outgoing call with the configured exception and records the assault, so the remote service is never
     * reached.
     *
     * @param context the Vert.x web client request context
     * @param config the active configuration
     * @param methodName the history identifier of the outgoing call
     */
    private static void failWithException(HttpContext<?> context, MutableAssaultConfig config, String methodName) {
        LOG.debugf("Goblin: injecting WebClient exception on %s", methodName);
        engine().recordAssault(AssaultSource.WEBCLIENT, methodName, "exception");
        context.fail(ExceptionAssault.createException(config));
    }

    /**
     * Waits for {@code delayMs} before running the follow-up action, without blocking the event loop: when there is a
     * current Vert.x context a timer is scheduled, otherwise the calling (non-Vert.x) thread is slept.
     *
     * @param context the Vert.x web client request context
     * @param delayMs the delay in milliseconds
     * @param after the action to run once the delay elapsed
     */
    private static void delayThen(HttpContext<?> context, long delayMs, Runnable after) {
        Vertx vertx = currentVertx();
        if (vertx != null) {
            vertx.setTimer(delayMs, id -> after.run());
        } else {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            after.run();
        }
    }

    /**
     * @return the {@code Vertx} owning the current thread's context, or {@code null} when no Vert.x context is active
     */
    private static Vertx currentVertx() {
        Context context = Vertx.currentContext();
        return context != null ? context.owner() : null;
    }

    /**
     * Produces the history identifier for an outbound call, e.g. {@code "WebClient GET http://localhost:8081/api/hello"}.
     * <p>
     * The target URL is reconstructed from the request's host, port and SSL flag so it carries the full remote address
     * rather than the possibly relative URI kept by {@link HttpRequest}.
     *
     * @param context the Vert.x web client request context
     * @return a human-readable identifier combining the HTTP method and the target URL
     */
    private static String describeCall(HttpContext<?> context) {
        HttpRequest<?> request = context.request();
        if (request == null) {
            return "WebClient <unknown>";
        }
        StringBuilder identifier = new StringBuilder("WebClient ").append(request.method());
        String host = request.host();
        if (host != null) {
            identifier.append(request.ssl() == Boolean.TRUE ? " https://" : " http://").append(host);
            if (request.port() > 0) {
                identifier.append(':').append(request.port());
            }
            identifier.append(stripQuery(request.uri()));
        } else if (request.uri() != null) {
            identifier.append(' ').append(stripQuery(request.uri()));
        }
        return identifier.toString();
    }

    /**
     * Drops the query string and fragment of a request URI: they routinely carry tokens that must not end up in the
     * history, the traces or the logs.
     *
     * @param uri the request URI, possibly {@code null}
     * @return the URI path only
     */
    static String stripQuery(String uri) {
        if (uri == null) {
            return "";
        }
        int cut = uri.length();
        int query = uri.indexOf('?');
        int fragment = uri.indexOf('#');
        if (query >= 0) {
            cut = query;
        }
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        return uri.substring(0, cut);
    }

    /**
     * Resolves the {@link AssaultEngine} of the running application from the CDI container.
     *
     * @return the application's assault engine, never {@code null}
     */
    private static AssaultEngine engine() {
        AssaultEngine current = testEngine;
        return current != null ? current : CDI.current().select(AssaultEngine.class).get();
    }

    /**
     * Replaces the engine used by the interceptor, intended for unit tests running outside the CDI container.
     *
     * @param testEngine the engine to use, or {@code null} to revert to the CDI lookup
     */
    static void setEngineForTests(AssaultEngine testEngine) {
        GoblinWebClient.testEngine = testEngine;
    }
}