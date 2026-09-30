package io.quarkiverse.goblin.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Uni;

/**
 * Resource shapes for issue #69, with and without {@code @Produces}: the HTTP status and dependency degradation
 * assaults must answer in the representation each endpoint answers when it is not assaulted.
 */
@Path("/api/chaos-content")
public class ChaosContentTypeResource {

    public record Greeting(String message) {
    }

    /** A POJO without {@code @Produces}, the most common Quarkus REST shape: JSON by default. */
    @GET
    @Path("/pojo")
    public Greeting pojo() {
        return new Greeting("hello");
    }

    @GET
    @Path("/pojo-explicit")
    @Produces(MediaType.APPLICATION_JSON)
    public Greeting pojoExplicit() {
        return new Greeting("hello explicit");
    }

    /** An asynchronous POJO without {@code @Produces}: JSON by default too. */
    @GET
    @Path("/uni-pojo")
    public Uni<Greeting> uniPojo() {
        return Uni.createFrom().item(new Greeting("hello later"));
    }

    /** A {@code String} without {@code @Produces}: text by default. */
    @GET
    @Path("/string")
    public String string() {
        return "hello";
    }

    @GET
    @Path("/text")
    @Produces(MediaType.TEXT_PLAIN)
    public String text() {
        return "hello";
    }

    /** A {@code String} explicitly declared as JSON. */
    @GET
    @Path("/explicit-json-string")
    @Produces(MediaType.APPLICATION_JSON)
    public String explicitJsonString() {
        return "{\"message\":\"hello explicit\"}";
    }

    /** No entity at all: a 204 when not assaulted. */
    @GET
    @Path("/no-entity")
    public void noEntity() {
    }
}
