package io.quarkiverse.goblin.it;

import jakarta.enterprise.context.RequestScoped;

/**
 * Request-scoped marker set by {@link SampleRouteFilters} and read by a route, proving a route delayed by Goblin still
 * runs inside the request context of its request.
 */
@RequestScoped
public class RouteTrace {

    private String value = "unset";

    public String get() {
        return value;
    }

    public void set(String value) {
        this.value = value;
    }
}
