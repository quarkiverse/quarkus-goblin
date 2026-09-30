package io.quarkiverse.goblin.assault;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

class AbortResponsesTest {

    @Produces(MediaType.APPLICATION_JSON)
    public static class JsonResource {
        @Produces(MediaType.APPLICATION_JSON)
        public String jsonMethod() {
            return "x";
        }

        public String inheritedMethod() {
            return "x";
        }
    }

    public static class TextResource {
        @Produces(MediaType.TEXT_PLAIN)
        public String textMethod() {
            return "x";
        }
    }

    @Produces(MediaType.TEXT_PLAIN)
    public static class TextClassResource {
        public String impliedText() {
            return "x";
        }
    }

    public static class PlainResource {
        public String noProduces() {
            return "x";
        }
    }

    public static class MultiResource {
        @Produces({ MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON })
        public String multi() {
            return "x";
        }
    }

    public static class SuffixResource {
        @Produces("application/hal+json")
        public String hal() {
            return "x";
        }
    }

    @Produces(MediaType.APPLICATION_JSON)
    public static class JsonClassWithTextMethod {
        @Produces(MediaType.TEXT_PLAIN)
        public String text() {
            return "x";
        }
    }

    public record Greeting(String message) {
    }

    /**
     * Resource methods without any {@code @Produces}: Quarkus REST picks the representation from the return type.
     */
    public static class DefaultResource {
        public Greeting pojo() {
            return new Greeting("x");
        }

        public List<Greeting> list() {
            return List.of();
        }

        public Map<String, Greeting> map() {
            return Map.of();
        }

        public CompletionStage<Greeting> async() {
            return null;
        }

        public String string() {
            return "x";
        }

        public int primitive() {
            return 1;
        }

        public Long boxed() {
            return 1L;
        }

        public void nothing() {
        }

        public Response response() {
            return null;
        }

        public byte[] bytes() {
            return new byte[0];
        }

        public Object anything() {
            return null;
        }

        public <T> T generic() {
            return null;
        }
    }

    private static Method method(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void methodJsonWins() {
        assertTrue(AbortResponses.wantsJson(method(JsonResource.class, "jsonMethod"), null));
    }

    @Test
    void classJsonAppliesWhenMethodHasNoProduces() {
        assertTrue(AbortResponses.wantsJson(method(JsonResource.class, "inheritedMethod"), JsonResource.class));
    }

    @Test
    void textMethodIsNotJson() {
        assertFalse(AbortResponses.wantsJson(method(TextResource.class, "textMethod"), null));
    }

    @Test
    void textClassIsNotJson() {
        assertFalse(AbortResponses.wantsJson(method(TextClassResource.class, "impliedText"), TextClassResource.class));
    }

    @Test
    void missingProducesFallsBackToText() {
        assertFalse(AbortResponses.wantsJson(method(PlainResource.class, "noProduces"), PlainResource.class));
        assertFalse(AbortResponses.wantsJson(null, null));
    }

    @Test
    void multiValuedProducesWithJsonSelectsJson() {
        assertTrue(AbortResponses.wantsJson(method(MultiResource.class, "multi"), null));
    }

    @Test
    void structuredJsonSuffixSelectsJson() {
        assertTrue(AbortResponses.wantsJson(method(SuffixResource.class, "hal"), null));
    }

    @Test
    void statusOnJsonIsParseableWithCode() {
        AbortResponses.Abort abort = AbortResponses.abort(503, "Service Unavailable (Goblin chaos)",
                method(JsonResource.class, "jsonMethod"), null);

        assertEquals(MediaType.APPLICATION_JSON_TYPE, abort.mediaType());
        assertEquals("{\"message\":\"Service Unavailable (Goblin chaos)\",\"code\":503}", abort.entity());
        assertEquals(503, abort.status());
    }

    @Test
    void statusOnTextKeepsRawMessage() {
        AbortResponses.Abort abort = AbortResponses.abort(503, "Service Unavailable (Goblin chaos)",
                method(TextResource.class, "textMethod"), null);

        assertEquals(MediaType.TEXT_PLAIN_TYPE, abort.mediaType());
        assertEquals("Service Unavailable (Goblin chaos)", abort.entity());
    }

    @Test
    void statusWithoutResourceFallsBackToText() {
        AbortResponses.Abort abort = AbortResponses.abort(429, "Too Many Requests", null, null);

        assertEquals(MediaType.TEXT_PLAIN_TYPE, abort.mediaType());
        assertEquals("Too Many Requests", abort.entity());
    }

    @Test
    void dependencyDegradationSharesTheSameContract() {
        AbortResponses.Abort json = AbortResponses.abort(503, AbortResponses.DEPENDENCY_MESSAGE,
                method(JsonResource.class, "jsonMethod"), null);
        assertEquals(503, json.status());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, json.mediaType());
        assertEquals("{\"message\":\"Dependency unavailable (Goblin chaos)\",\"code\":503}", json.entity());

        AbortResponses.Abort text = AbortResponses.abort(503, AbortResponses.DEPENDENCY_MESSAGE,
                method(TextResource.class, "textMethod"), null);
        assertEquals(MediaType.TEXT_PLAIN_TYPE, text.mediaType());
        assertEquals("Dependency unavailable (Goblin chaos)", text.entity());
    }

    @Test
    void jsonBodyEscapesQuotesAndControls() {
        assertEquals("{\"message\":\"a\\\"b\\\\c\\n\",\"code\":500}", AbortResponses.jsonBody("a\"b\\c\n", 500));
    }

    @Test
    void theMethodProducesWinsOverTheClassOne() {
        assertFalse(AbortResponses.wantsJson(method(JsonClassWithTextMethod.class, "text"), JsonClassWithTextMethod.class),
                "a text/plain method stays text in a JSON resource class");
    }

    @Test
    void withoutProducesAPojoCollectionMapOrAsyncPojoIsJson() {
        for (String name : List.of("pojo", "list", "map", "async")) {
            assertTrue(AbortResponses.wantsJson(method(DefaultResource.class, name), DefaultResource.class), name);
        }
    }

    @Test
    void withoutProducesStringsScalarsVoidAndRawResponsesAreText() {
        for (String name : List.of("string", "primitive", "boxed", "nothing", "response", "bytes", "anything", "generic")) {
            assertFalse(AbortResponses.wantsJson(method(DefaultResource.class, name), DefaultResource.class), name);
        }
    }
}
