package io.quarkiverse.goblin.it.excluded;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.goblin.it.SampleService;

/**
 * An entry point living in its own package, so a test profile can exclude it with
 * {@code quarkus.goblin.target.exclude-packages} while the {@link SampleService} it calls stays targeted.
 */
@Path("/api/excluded")
@Produces(MediaType.TEXT_PLAIN)
public class ExcludedResource {

    @Inject
    SampleService sampleService;

    @GET
    @Path("/service")
    public String service() {
        return sampleService.hello();
    }

    @GET
    @Path("/db")
    public String database() {
        return sampleService.databasePing();
    }
}
