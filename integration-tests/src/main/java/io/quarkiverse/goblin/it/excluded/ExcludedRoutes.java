package io.quarkiverse.goblin.it.excluded;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.goblin.it.SampleService;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HandlerType;
import io.quarkus.vertx.web.Route.HttpMethod;

/**
 * The reactive-route counterpart of {@link ExcludedResource}: an entry point a test profile excludes with
 * {@code quarkus.goblin.target.exclude-packages}, while the {@link SampleService} it calls stays targeted.
 */
@ApplicationScoped
public class ExcludedRoutes {

    @Inject
    SampleService sampleService;

    @Route(path = "/routes/excluded/service", methods = HttpMethod.GET, produces = "text/plain", type = HandlerType.BLOCKING)
    public String service() {
        return sampleService.hello();
    }
}
