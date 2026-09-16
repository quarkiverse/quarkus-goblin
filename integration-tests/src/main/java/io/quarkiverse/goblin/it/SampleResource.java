package io.quarkiverse.goblin.it;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
public class SampleResource {

    @Inject
    @RestClient
    SampleClient sampleClient;

    @GET
    @Path("/hello")
    public String hello() {
        return "hello from Goblin test app";
    }

    @GET
    @Path("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(10);
        return "this endpoint has built-in delay";
    }

    @GET
    @Path("/unstable")
    public String unstable() {
        return "this endpoint should fail when chaos injects exceptions";
    }

    @GET
    @Path("/proxy")
    public String proxyHello() {
        return sampleClient.hello();
    }
}
