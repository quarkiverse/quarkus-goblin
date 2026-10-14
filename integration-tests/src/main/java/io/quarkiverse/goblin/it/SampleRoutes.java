package io.quarkiverse.goblin.it;

import java.time.Duration;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.Body;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HandlerType;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RouteBase;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.ext.web.RoutingContext;

/**
 * Reactive routes ({@code @Route}) covered by the HTTP_IN layer through {@code GoblinRouteHook} (issue #64): a
 * non-blocking route served on the event loop, a blocking one, a JSON one, one calling the targeted
 * {@link SampleService}, and a failure handler that must answer injected exceptions instead of being assaulted itself.
 */
@ApplicationScoped
@RouteBase(path = "/routes")
public class SampleRoutes {

    @Inject
    SampleService sampleService;

    @Inject
    RouteTrace trace;

    @Route(path = "hello", methods = { HttpMethod.GET, HttpMethod.HEAD }, produces = "text/plain")
    public String hello() {
        return "hello from Goblin route";
    }

    @Route(path = "thread", methods = HttpMethod.GET, produces = "text/plain")
    public String thread() {
        return Context.isOnEventLoopThread() ? "event-loop" : "worker";
    }

    @Route(path = "blocking", methods = HttpMethod.GET, produces = "text/plain", type = HandlerType.BLOCKING)
    public String blocking() {
        return "blocking hello from Goblin route";
    }

    @Route(path = "json", methods = HttpMethod.GET, produces = "application/json")
    public Map<String, String> json() {
        return Map.of("greeting", "hello from Goblin route");
    }

    @Route(path = "service", methods = HttpMethod.GET, produces = "text/plain", type = HandlerType.BLOCKING)
    public String service() {
        return sampleService.hello();
    }

    @Route(path = "trace", methods = HttpMethod.GET, produces = "text/plain")
    public String trace() {
        return trace.get();
    }

    @Route(path = "echo", methods = HttpMethod.POST, produces = "text/plain")
    public String echo(@Body String body) {
        return "echo: " + body;
    }

    @Route(path = "fluent", methods = HttpMethod.GET)
    void fluent(RoutingContext context) {
        context.response().setStatusCode(201).putHeader("X-Fluent", "yes").end("fluent hello from Goblin route");
    }

    @Route(path = "json-default", methods = HttpMethod.GET)
    void jsonDefault(RoutingContext context) {
        context.put("goblin", "json").json(Map.of("greeting", "hello"));
    }

    @Route(path = "uni", methods = HttpMethod.GET, produces = "text/plain")
    public Uni<String> uni() {
        return Uni.createFrom().item("uni hello from Goblin route").onItem().delayIt().by(Duration.ofMillis(10));
    }

    @Route(path = "multi", methods = HttpMethod.GET, produces = "text/plain")
    public Multi<String> multi() {
        return Multi.createFrom().items("streamed ", "hello");
    }

    @Route(path = "boom", methods = HttpMethod.GET, produces = "text/plain")
    public String boom() {
        throw new IllegalStateException("boom");
    }

    @Route(path = "/*", type = HandlerType.FAILURE)
    void onFailure(RoutingContext context) {
        Throwable failure = context.failure();
        context.response().setStatusCode(500).putHeader("Content-Type", "text/plain")
                .end("route failure handled: " + (failure != null ? failure.getMessage() : "none"));
    }
}
