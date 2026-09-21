package io.quarkiverse.goblin.it;

import java.util.concurrent.CompletionException;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkiverse.goblin.GoblinWebClient;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
@ApplicationScoped
public class SampleResource {

    @Inject
    @RestClient
    SampleClient sampleClient;

    @Inject
    Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void armWebClient() {
        this.webClient = GoblinWebClient.enable(WebClient.create(vertx));
    }

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

    @GET
    @Path("/web-proxy")
    public String webProxyHello(@Context UriInfo uriInfo) {
        String downstream = uriInfo.getBaseUri().resolve("/api/hello").toString();
        try {
            HttpResponse<Buffer> response = webClient.getAbs(downstream).send()
                    .toCompletionStage().toCompletableFuture().join();
            return response.bodyAsString();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("WebClient call failed", e.getCause());
        }
    }
}
