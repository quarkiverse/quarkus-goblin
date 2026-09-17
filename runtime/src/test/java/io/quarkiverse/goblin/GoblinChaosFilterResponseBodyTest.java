package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        inject(filter, "config", noFilteringConfig());
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
        assertEquals("response-body-truncate", engine.getHistory().get(0).type());
        assertEquals(TestResource.class.getSimpleName() + ".hello", engine.getHistory().get(0).method());
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
        assertEquals("response-body-inflate", engine.getHistory().get(0).type());
    }

    @Test
    void truncateKeepsExactBytesWhenCutSplitsAMultibyteCharacter() throws Exception {
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        config.setResponseBodyPercentage(50);

        FakeResponse response = new FakeResponse("aé", MediaType.TEXT_PLAIN_TYPE);
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
        assertEquals(6, result.length);
        assertArrayEquals("hello ".getBytes(java.nio.charset.StandardCharsets.UTF_8), result);
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

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = fieldName.equals("mutableConfig") ? AssaultEngine.class.getDeclaredField(fieldName)
                : GoblinChaosFilter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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

    private static GoblinConfig noFilteringConfig() {
        return new GoblinConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public AssaultConfig assault() {
                throw new UnsupportedOperationException();
            }

            @Override
            public TargetConfig target() {
                return new TargetConfig() {
                    @Override
                    public int level() {
                        return 100;
                    }

                    @Override
                    public java.util.Optional<String[]> includePackages() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String[]> excludePackages() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String[]> excludeAnnotations() {
                        return java.util.Optional.empty();
                    }
                };
            }
        };
    }

    private static ContainerRequestContext requestContext() {
        InvocationHandler handler = (proxy, m, args) -> {
            if (m.getName().equals("toString")) {
                return "fake ContainerRequestContext";
            }
            return defaultValue(m.getReturnType());
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
