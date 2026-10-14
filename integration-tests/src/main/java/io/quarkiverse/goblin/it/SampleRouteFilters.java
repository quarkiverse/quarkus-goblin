package io.quarkiverse.goblin.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteFilter;
import io.smallrye.common.annotation.NonBlocking;
import io.vertx.ext.web.RoutingContext;

/**
 * Route plumbing the reactive-route hook must leave alone or assault exactly once: a {@code @RouteFilter} (never
 * assaulted) and a {@code @Route} acting as a pass-through filter in front of a REST resource (assaulted in place of the
 * resource, never on top of it).
 */
@ApplicationScoped
public class SampleRouteFilters {

    @Inject
    RouteTrace trace;

    @RouteFilter
    void mark(RoutingContext context) {
        if (context.normalizedPath().startsWith("/routes/")) {
            trace.set("filtered");
            context.response().putHeader("X-Goblin-Filter", "seen");
        }
        context.next();
    }

    /**
     * A pass-through route an exclusion profile spares with {@code exclude-annotations}: the REST resource behind it
     * stays targeted. {@code @NonBlocking} is already a reactive route's default, so it changes nothing else.
     */
    @Route(regex = "/api/spared/.*", order = 1)
    @NonBlocking
    void sparedPassThrough(RoutingContext context) {
        context.response().putHeader("X-Goblin-Spared", "seen");
        context.next();
    }

    @Route(regex = "/api/passthrough/.*", order = 1)
    void passThrough(RoutingContext context) {
        context.response().putHeader("X-Goblin-Pass-Through", "seen");
        context.next();
    }
}
