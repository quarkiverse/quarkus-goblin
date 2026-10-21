package io.quarkiverse.goblin.internal.route;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.ResponseBodyMode;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;
import io.quarkiverse.goblin.internal.MutableAssaultConfigImpl;
import io.quarkiverse.goblin.internal.assault.ResponseBodyTransformer;
import io.quarkiverse.goblin.internal.assault.ResponseHeaderTransformer;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * The response assaults on reactive routes:
 * <ul>
 * <li>the response header rules run in a headers-end handler registered before the route method runs, so -- Vert.x
 * running those handlers in reverse order -- after the default {@code Content-Type} the generated handler sets;</li>
 * <li>for the response body assault, the route method receives a routing context whose response rewrites the payload of a
 * single-shot {@code end(String)} /
 * {@code end(Buffer)} -- what the generated handler of a route returning a value writes, and what a route writing its
 * own response usually calls.</li>
 * </ul>
 * <p>
 * Like on a REST resource, only a complete payload is transformed: a response streamed with {@code write(...)} (a
 * {@code Multi}, server-sent events), chunked, or sent from a file is left untouched. The {@code Content-Length} follows
 * the REST behaviour: {@link ResponseBodyMode#TRUNCATE} declares the truncated length, {@link ResponseBodyMode#INFLATE}
 * keeps advertising the original, smaller one.
 */
final class RouteResponse {

    private static final Logger LOG = Logger.getLogger(RouteResponse.class);

    private static final String CONTENT_LENGTH = "Content-Length";

    private static final String CONTENT_TYPE = "Content-Type";

    private RouteResponse() {
    }

    /**
     * Arms the response assaults enabled in the configuration on what the route writes.
     *
     * @param context the routing context of the assaulted request
     * @param config the active configuration
     * @param engine the engine recording the assaults
     * @param description the history identifier of the route
     * @return the routing context to hand to the route method: {@code context} itself, or a wrapper rewriting the
     *         response body
     */
    static RoutingContext wrap(RoutingContext context, MutableAssaultConfigImpl config, AssaultEngineImpl engine,
            String description) {
        HttpServerResponse real = context.response();
        if (config.isResponseHeaderEnabled()) {
            // Vert.x runs the headers-end handlers in reverse order: registered before the route method runs, the rules
            // apply after the default Content-Type the generated handler sets
            boolean removesContentType = config.getResponseHeaders().entrySet().stream()
                    .anyMatch(rule -> CONTENT_TYPE.equalsIgnoreCase(rule.getKey())
                            && rule.getValue().action() == ResponseHeaderAction.REMOVE);
            context.addHeadersEndHandler(ignored -> {
                ResponseHeaderTransformer.apply(headers(real), config, engine, description);
                if (removesContentType) {
                    // a @RouteFilter registered before the rules sets the Content-Type from the acceptable type when
                    // none is present: without one, a removed Content-Type stays removed
                    context.setAcceptableContentType(null);
                }
            });
        }
        if (!config.isResponseBodyEnabled()) {
            return context;
        }
        ResponseHandler handler = new ResponseHandler(real, config, engine, description);
        HttpServerResponse response = proxy(HttpServerResponse.class, handler);
        handler.self = response;
        return proxy(RoutingContext.class, (proxy, method, args) -> {
            if (method.getName().equals("response") && method.getParameterCount() == 0) {
                return response;
            }
            Object result = forward(proxy, context, method, args);
            // keep fluent chains such as put(...).json(...) on the proxy
            return result == context ? proxy : result;
        });
    }

    /**
     * Adapts the Vert.x response headers, which are case-insensitive, to the header assault.
     */
    private static ResponseHeaderTransformer.Headers headers(HttpServerResponse response) {
        return new ResponseHeaderTransformer.Headers() {
            @Override
            public boolean remove(String name) {
                boolean present = response.headers().contains(name);
                response.headers().remove(name);
                return present;
            }

            @Override
            public void set(String name, String value) {
                response.headers().set(name, value);
            }
        };
    }

    /**
     * Forwards a call to the wrapped object. Default interface methods run on the proxy, so the calls they make
     * internally (e.g. {@code RoutingContext.end(String)} calling {@code response()}) are intercepted too.
     */
    private static Object forward(Object proxy, Object target, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> method.invoke(target, args);
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RouteResponse.class.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static final class ResponseHandler implements InvocationHandler {

        private final HttpServerResponse response;
        private final MutableAssaultConfigImpl config;
        private final AssaultEngineImpl engine;
        private final String description;
        private HttpServerResponse self;
        private boolean streamed;
        private boolean done;

        ResponseHandler(HttpServerResponse response, MutableAssaultConfigImpl config, AssaultEngineImpl engine,
                String description) {
            this.response = response;
            this.config = config;
            this.engine = engine;
            this.description = description;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("end") && !method.isDefault() && args != null && !streamed && !done
                    && (args[0] instanceof String || args[0] instanceof Buffer)) {
                done = true;
                return end(args);
            }
            if (name.equals("write") || name.startsWith("sendFile")
                    || name.equals("setChunked") && Boolean.TRUE.equals(args[0])) {
                streamed = true;
            }
            Object result = forward(proxy, response, method, args);
            // keep fluent chains such as response().putHeader(...).end(...) on the proxy
            return result == response ? self : result;
        }

        @SuppressWarnings("unchecked")
        private Object end(Object[] args) {
            byte[] body;
            if (args[0] instanceof Buffer buffer) {
                body = buffer.getBytes();
            } else {
                Charset charset = args.length > 1 && args[1] instanceof String encoding ? Charset.forName(encoding)
                        : StandardCharsets.UTF_8;
                body = ((String) args[0]).getBytes(charset);
            }
            ResponseBodyMode mode = config.getResponseBodyMode();
            int declaredLength = declaredLength(body);
            byte[] transformed = ResponseBodyTransformer.transform(body, mode, config.getResponseBodyPercentage());
            int declared = mode == ResponseBodyMode.INFLATE ? declaredLength : transformed.length;
            response.putHeader(CONTENT_LENGTH, Integer.toString(declared));
            LOG.debugf("Goblin: response body %s on reactive route %s (Content-Length %d, actual %d bytes)",
                    mode.name().toLowerCase(), description, declared, transformed.length);
            engine.recordAssault(AssaultType.RESPONSE_BODY, description, "response-body-" + mode.name().toLowerCase());
            Buffer payload = Buffer.buffer(transformed);
            if (args[args.length - 1] instanceof Handler<?> callback) {
                response.end(payload, (Handler) callback);
                return null;
            }
            return response.end(payload);
        }

        private int declaredLength(byte[] body) {
            String header = response.headers().get(CONTENT_LENGTH);
            if (header != null) {
                try {
                    return Integer.parseInt(header.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the payload length
                }
            }
            return body.length;
        }
    }
}
