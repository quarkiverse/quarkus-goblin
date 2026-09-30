package io.quarkiverse.goblin.assault;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Shared abort builder for the HTTP status and dependency degradation assaults (issue #69).
 * <p>
 * Both assaults abort with a plain {@code String} message but no explicit media type, so Quarkus REST negotiated the
 * {@code Content-Type} from the resource method: a JSON resource answered a plain-text body declared as
 * {@code application/json}. The fix is shared here instead of duplicated per assault: when the targeted resource
 * declares a JSON type in its {@code @Produces} (method first, then class), the abort carries an explicit
 * {@code application/json} type with a parseable {@code {"message":"...","code":...}} body; otherwise it carries an
 * explicit {@code text/plain} type with the raw message. Without any {@code @Produces}, the representation follows the
 * Quarkus REST default for the method's return type (see {@link #wantsJson(Method, Class)}).
 */
public final class AbortResponses {

    /** Fixed body of the dependency degradation assault, also used as its JSON {@code message} field. */
    public static final String DEPENDENCY_MESSAGE = "Dependency unavailable (Goblin chaos)";

    /**
     * Wrappers around the entity a resource method eventually answers, compared by name so the runtime module needs no
     * Mutiny nor Quarkus REST dependency ({@link CompletionStage} and its subtypes are matched by type).
     */
    private static final Set<String> WRAPPERS = Set.of("io.smallrye.mutiny.Uni", "io.smallrye.mutiny.Multi",
            "org.jboss.resteasy.reactive.RestResponse");

    private AbortResponses() {
    }

    /**
     * Abort payload with a body valid for its declared content type, without requiring a JAX-RS runtime: unit tests
     * assert on it directly, the filter path converts it with {@link #status(int, String, Method, Class)}.
     *
     * @param status the HTTP status code
     * @param mediaType the explicit media type, never {@code null}
     * @param entity the body, never {@code null}
     */
    public record Abort(int status, MediaType mediaType, String entity) {
        Response toResponse() {
            return Response.status(status).type(mediaType).entity(entity).build();
        }
    }

    /**
     * Computes the abort payload with a body valid for its declared content type.
     *
     * @param status the HTTP status code
     * @param message the human-readable message, used raw for {@code text/plain} and as the {@code message} field for JSON
     * @param resourceMethod the targeted resource method, may be {@code null}
     * @param resourceClass the targeted resource class, may be {@code null}
     * @return the abort payload
     */
    public static Abort abort(int status, String message, Method resourceMethod, Class<?> resourceClass) {
        String safeMessage = message != null ? message : "";
        if (wantsJson(resourceMethod, resourceClass)) {
            return new Abort(status, MediaType.APPLICATION_JSON_TYPE, jsonBody(safeMessage, status));
        }
        return new Abort(status, MediaType.TEXT_PLAIN_TYPE, safeMessage);
    }

    /**
     * Builds an abort response with a body valid for its declared content type.
     *
     * @param status the HTTP status code
     * @param message the human-readable message, used raw for {@code text/plain} and as the {@code message} field for JSON
     * @param resourceMethod the targeted resource method, may be {@code null}
     * @param resourceClass the targeted resource class, may be {@code null}
     * @return the response to pass to {@code abortWith}
     */
    public static Response status(int status, String message, Method resourceMethod, Class<?> resourceClass) {
        return abort(status, message, resourceMethod, resourceClass).toResponse();
    }

    /**
     * Builds the fixed {@code 503} abort of the dependency degradation assault.
     *
     * @param resourceMethod the targeted resource method, may be {@code null}
     * @param resourceClass the targeted resource class, may be {@code null}
     * @return the response to pass to {@code abortWith}
     */
    public static Response dependencyDegradation(Method resourceMethod, Class<?> resourceClass) {
        return status(503, DEPENDENCY_MESSAGE, resourceMethod, resourceClass);
    }

    /**
     * Returns whether the targeted resource answers JSON, in which case the abort must be JSON to stay consistent with
     * the contract its clients rely on.
     * <p>
     * The closest {@code @Produces} decides: the method's, else the resource class's, else the method declaring
     * class's. Any JSON-compatible value ({@code application/json} or {@code *+json}, parameters ignored) selects JSON,
     * anything else -- wildcards, text types -- selects {@code text/plain}, so a {@code text/plain} method stays text
     * even in a JSON resource class.
     * <p>
     * Without any {@code @Produces}, Quarkus REST picks the representation from the return type, so the abort does the
     * same: a POJO, a collection or a map -- also wrapped in {@code Uni}, {@code Multi}, {@code CompletionStage} or
     * {@code RestResponse} -- is serialised as JSON, while a {@code String}, a primitive, {@code void} or a raw
     * {@code Response} (whose representation is unknown before it runs) gets {@code text/plain}.
     *
     * @param resourceMethod the targeted resource method, may be {@code null}
     * @param resourceClass the targeted resource class, may be {@code null}
     * @return {@code true} when the abort body must be JSON
     */
    public static boolean wantsJson(Method resourceMethod, Class<?> resourceClass) {
        Produces produces = closestProduces(resourceMethod, resourceClass);
        if (produces != null) {
            return containsJson(produces.value());
        }
        return resourceMethod != null && producesJsonByDefault(resourceMethod.getGenericReturnType());
    }

    private static Produces closestProduces(Method resourceMethod, Class<?> resourceClass) {
        if (resourceMethod != null && resourceMethod.getAnnotation(Produces.class) != null) {
            return resourceMethod.getAnnotation(Produces.class);
        }
        if (resourceClass != null && resourceClass.getAnnotation(Produces.class) != null) {
            return resourceClass.getAnnotation(Produces.class);
        }
        return resourceMethod != null ? resourceMethod.getDeclaringClass().getAnnotation(Produces.class) : null;
    }

    /**
     * Mirrors the Quarkus REST default for a resource method without {@code @Produces}: text for strings and scalars,
     * JSON for everything else it serialises.
     *
     * @param type the generic return type of the resource method
     * @return {@code true} when the endpoint answers JSON by default
     */
    static boolean producesJsonByDefault(Type type) {
        Type unwrapped = unwrap(type);
        if (!(unwrapped instanceof Class<?> || unwrapped instanceof ParameterizedType
                || unwrapped instanceof GenericArrayType)) {
            // a type variable or a wildcard: the representation is only known at runtime
            return false;
        }
        Class<?> raw = rawType(unwrapped);
        if (raw == null) {
            return false;
        }
        return !(raw.isPrimitive() || raw == Void.class || raw == Object.class || raw == byte[].class
                || CharSequence.class.isAssignableFrom(raw) || Number.class.isAssignableFrom(raw)
                || raw == Boolean.class || raw == Character.class || Response.class.isAssignableFrom(raw)
                || java.io.InputStream.class.isAssignableFrom(raw) || java.io.File.class.isAssignableFrom(raw)
                || java.nio.file.Path.class.isAssignableFrom(raw));
    }

    /**
     * Unwraps the asynchronous and response wrappers Quarkus REST looks through to find the serialised entity type.
     */
    private static Type unwrap(Type type) {
        Type current = type;
        for (int depth = 0; depth < 4 && current instanceof ParameterizedType parameterized; depth++) {
            Class<?> raw = rawType(parameterized);
            if (raw == null || !WRAPPERS.contains(raw.getName()) && !CompletionStage.class.isAssignableFrom(raw)) {
                break;
            }
            current = parameterized.getActualTypeArguments()[0];
        }
        return current;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        return null;
    }

    private static boolean containsJson(String[] values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (isJsonCompatible(value)) {
                return true;
            }
        }
        return false;
    }

    static boolean isJsonCompatible(String value) {
        if (value == null) {
            return false;
        }
        String type = value.trim().toLowerCase(Locale.ROOT);
        int params = type.indexOf(';');
        if (params >= 0) {
            type = type.substring(0, params).trim();
        }
        return type.equals(MediaType.APPLICATION_JSON) || type.endsWith("+json");
    }

    /**
     * Builds the stable JSON error body, e.g. {@code {"message":"...","code":503}}.
     *
     * @param message the human-readable message
     * @param code the HTTP status code, echoed so agents can classify the failure from the body alone
     * @return the JSON document
     */
    public static String jsonBody(String message, int code) {
        return "{\"message\":\"" + escapeJson(message != null ? message : "") + "\",\"code\":" + code + "}";
    }

    static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
