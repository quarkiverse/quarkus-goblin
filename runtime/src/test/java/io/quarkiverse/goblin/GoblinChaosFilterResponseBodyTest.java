package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.assault.Assault;

/**
 * Unit tests for the response-phase body assault of {@link GoblinChaosFilter}, using fake JAX-RS containers and a real
 * {@link AssaultEngine} so the transformation and history recording can be asserted without a running server.
 */
class GoblinChaosFilterResponseBodyTest {

    private final AssaultEngine engine = new AssaultEngine();
    private final GoblinChaosFilter filter = new GoblinChaosFilter();
    private MutableAssaultConfig config;

    @BeforeEach
    void setUp() throws Exception {
        engine.setActive(true);
        config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);
        inject(engine, "mutableConfig", config);
        inject(filter, "engine", engine);
        inject(filter, "resourceInfo", resourceInfo());
        inject(filter, "targeting", noFilteringConfig());
        inject(filter, "assaults", emptyAssaults());
    }

    @Test
    void truncateAltersEntityAndRecords() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        config.setResponseBodyPercentage(50);

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(), response.proxy());

        byte[] result = (byte[]) response.entity();
        assertEquals(13, result.length);
        assertArrayEquals("hello from Go".getBytes(java.nio.charset.StandardCharsets.UTF_8), result);
        assertEquals("13", response.header("Content-Length"),
                "TRUNCATE keeps the response well-framed: the declared length matches the truncated payload");
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-body-truncate", engine.getHistory().getFirst().type());
        assertEquals(TestResource.class.getSimpleName() + ".hello", engine.getHistory().getFirst().method());
    }

    @Test
    void inflatePadsEntityAndAdvertisesTheOriginalLength() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(200);

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE)
                .withContentLength(26);
        filter.filter(requestContext(), response.proxy());

        byte[] result = (byte[]) response.entity();
        assertEquals(26 * 2, result.length);
        assertArrayEquals("hello from Goblin test app".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Arrays.copyOf(result, 26));
        assertEquals("26", response.header("Content-Length"),
                "INFLATE advertises the original, smaller length so the header and the emitted payload diverge");
        assertEquals(1, engine.getHistory().size());
        assertEquals("response-body-inflate", engine.getHistory().getFirst().type());
    }

    @Test
    void truncateKeepsExactBytesWhenCutSplitsAMultibyteCharacter() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        config.setResponseBodyPercentage(50);

        FakeResponse response = new FakeResponse("aéb", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(), response.proxy());

        byte[] result = (byte[]) response.entity();
        assertArrayEquals(new byte[] { 0x61, (byte) 0xC3 }, result,
                "the payload must be the exact byte prefix, not a UTF-8 round-trip with replacement characters");
        assertEquals("2", response.header("Content-Length"));
    }

    @Test
    void bodyAssaultOffLeavesEntityUntouched() throws Exception {
        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(), response.proxy());

        assertEquals("hello from Goblin test app", response.entity());
        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void byteArrayEntityIsTransformedAndKeptAsBytes() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyPercentage(50);

        byte[] payload = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeResponse response = new FakeResponse(payload, MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(), response.proxy());

        assertTrue(response.entity() instanceof byte[]);
        byte[] result = (byte[]) response.entity();
        assertEquals(5, result.length);
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), result);
        assertEquals(1, engine.getHistory().size());
    }

    @Test
    void inactiveEngineSkipsTransformation() throws Exception {
        engine.setActive(false);
        config.setResponseBodyEnabled(true);

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(), response.proxy());

        assertEquals("hello from Goblin test app", response.entity());
        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void responsePhaseReusesTheRequestPhaseGateInsteadOfRollingAgain() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyPercentage(50);
        config.setTargetLevel(100);

        Map<String, Object> properties = new HashMap<>();
        ContainerRequestContext request = requestContext(properties);
        filter.filter(request);
        assertEquals(Boolean.TRUE, properties.get(GoblinChaosFilter.GATED_PROPERTY));

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(request, response.proxy());

        assertEquals(13, ((byte[]) response.entity()).length,
                "the response phase must apply to the request selected by the request phase");
        assertEquals(1, engine.getHistory().size());
    }

    @Test
    void responsePhaseSkipsWhenTheRequestPhaseGateDidNotSelectTheRequest() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyPercentage(50);
        config.setTargetLevel(100);

        Map<String, Object> properties = new HashMap<>();
        properties.put(GoblinChaosFilter.GATED_PROPERTY, Boolean.FALSE);

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(properties), response.proxy());

        assertEquals("hello from Goblin test app", response.entity(),
                "the response phase must not draw a second random number");
        assertTrue(engine.getHistory().isEmpty());
    }

    @Test
    void responsePhaseAppliesWhenTheStoredGatePassedEvenBelowTheCurrentLevel() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyPercentage(50);
        config.setTargetLevel(0);

        Map<String, Object> properties = new HashMap<>();
        properties.put(GoblinChaosFilter.GATED_PROPERTY, Boolean.TRUE);

        FakeResponse response = new FakeResponse("hello from Goblin test app", MediaType.TEXT_PLAIN_TYPE);
        filter.filter(requestContext(properties), response.proxy());

        assertEquals(13, ((byte[]) response.entity()).length,
                "the stored request-phase decision wins over a re-evaluation of the target level");
        assertEquals(1, engine.getHistory().size());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = fieldName.equals("mutableConfig") ? AssaultEngine.class.getDeclaredField(fieldName)
                : GoblinChaosFilter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Instance<Assault> emptyAssaults() {
        InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
            case "stream" -> Stream.empty();
            case "toString" -> "empty assaults";
            default -> defaultValue(m.getReturnType());
        };
        return (Instance<Assault>) Proxy.newProxyInstance(Instance.class.getClassLoader(),
                new Class<?>[] { Instance.class }, handler);
    }

    private static ResourceInfo resourceInfo() throws Exception {
        InvocationHandler handler = (proxy, m, args) -> switch (m.getName()) {
            case "getResourceMethod" -> TestResource.class.getMethod("hello");
            case "getResourceClass" -> TestResource.class;
            case "toString" -> "fake ResourceInfo";
            default -> null;
        };
        return (ResourceInfo) Proxy.newProxyInstance(ResourceInfo.class.getClassLoader(),
                new Class<?>[] { ResourceInfo.class }, handler);
    }

    private static GoblinTargetingConfig noFilteringConfig() {
        return new GoblinTargetingConfig() {
            @Override
            public Optional<List<String>> includePackages() {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> excludePackages() {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> excludeAnnotations() {
                return Optional.empty();
            }
        };
    }

    private static ContainerRequestContext requestContext() {
        return requestContext(new HashMap<>());
    }

    private static ContainerRequestContext requestContext(Map<String, Object> properties) {
        InvocationHandler handler = (proxy, m, args) -> {
            switch (m.getName()) {
                case "setProperty" -> {
                    properties.put((String) args[0], args[1]);
                    return null;
                }
                case "getProperty" -> {
                    return properties.get((String) args[0]);
                }
                case "toString" -> {
                    return "fake ContainerRequestContext";
                }
                default -> {
                    return defaultValue(m.getReturnType());
                }
            }
        };
        return (ContainerRequestContext) Proxy.newProxyInstance(ContainerRequestContext.class.getClassLoader(),
                new Class<?>[] { ContainerRequestContext.class }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    public static class TestResource {
        public String hello() {
            return "hello";
        }
    }

    /**
     * Minimal mutable fake of {@link ContainerResponseContext} holding the entity and media type needed by the filter.
     */
    private static final class FakeResponse {

        private Object entity;
        private final MediaType mediaType;
        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        FakeResponse(Object entity, MediaType mediaType) {
            this.entity = entity;
            this.mediaType = mediaType;
        }

        FakeResponse withContentLength(int length) {
            headers.putSingle("Content-Length", Integer.toString(length));
            return this;
        }

        Object entity() {
            return entity;
        }

        String header(String name) {
            Object value = headers.getFirst(name);
            return value != null ? value.toString() : null;
        }

        ContainerResponseContext proxy() {
            InvocationHandler handler = (proxy, m, args) -> {
                if (m.getName().equals("getEntity")) {
                    return entity;
                }
                if (m.getName().equals("setEntity")) {
                    entity = args[0];
                    return null;
                }
                if (m.getName().equals("getMediaType")) {
                    return mediaType;
                }
                if (m.getName().equals("getHeaders")) {
                    return headers;
                }
                if (m.getName().equals("getHeaderString")) {
                    Object value = headers.getFirst((String) args[0]);
                    return value != null ? value.toString() : null;
                }
                if (m.getName().equals("toString")) {
                    return "fake ContainerResponseContext";
                }
                return defaultValue(m.getReturnType());
            };
            return (ContainerResponseContext) Proxy.newProxyInstance(
                    ContainerResponseContext.class.getClassLoader(),
                    new Class<?>[] { ContainerResponseContext.class },
                    handler);
        }
    }
}
