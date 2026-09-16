package io.quarkiverse.goblin.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client pointing at the local test application, used to exercise client-side assaults on outbound calls.
 */
@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
@RegisterRestClient(configKey = "sample-client")
public interface SampleClient {

    @GET
    @Path("/hello")
    String hello();
}