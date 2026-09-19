package io.quarkiverse.goblin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.assault.Assault;
import io.quarkiverse.goblin.assault.AssaultContext;
import io.quarkiverse.goblin.assault.AssaultOutcome;

@Provider
@ApplicationScoped
public class GoblinChaosFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(GoblinChaosFilter.class);

    @Inject
    Instance<Assault> assaults;

    @Inject
    AssaultEngine engine;

    @Inject
    ResourceInfo resourceInfo;

    @Inject
    GoblinConfig config;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!engine.isActive() || !engine.shouldAssault()) {
            return;
        }

        if (!isTargetEligible()) {
            return;
        }

        String methodName = describeMethod();
        MutableAssaultConfig cfg = engine.getMutableConfig();
        AssaultContext context = new AssaultContext(requestContext, cfg, engine, methodName);

        for (Assault assault : assaults.stream()
                .sorted(Comparator.comparingInt(Assault::order))
                .toList()) {
            if (!assault.isEnabled(cfg)) {
                continue;
            }
            if (assault.apply(context) == AssaultOutcome.ABORTED) {
                return;
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        if (!engine.isActive() || !engine.shouldAssault()) {
            return;
        }
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null) {
            return;
        }
        if (!isTargetEligible()) {
            return;
        }
        if (cfg.isResponseBodyEnabled()) {
            byte[] body = toBytes(responseContext.getEntity());
            if (body != null) {
                int declaredLength = declaredLength(responseContext, body);
                byte[] transformed = ResponseBodyTransformer.transform(body, cfg.getResponseBodyMode(),
                        cfg.getResponseBodyPercentage());
                setEntity(responseContext, transformed);
                applyContentLength(responseContext, cfg.getResponseBodyMode(), declaredLength, transformed.length);
                int declared = cfg.getResponseBodyMode() == ResponseBodyMode.INFLATE ? declaredLength : transformed.length;
                LOG.debugf("Goblin: response body %s on %s (Content-Length %d, actual %d bytes)",
                        cfg.getResponseBodyMode().name().toLowerCase(), describeMethod(), declared, transformed.length);
                engine.recordAssault(describeMethod(), "response-body-" + cfg.getResponseBodyMode().name().toLowerCase());
            }
        }
        if (cfg.isResponseHeaderEnabled()) {
            ResponseHeaderTransformer.apply(responseContext, cfg, engine, describeMethod());
        }
    }

    /**
     * Returns the length advertised for the original body: the existing {@code Content-Length} header when present and
     * parseable, otherwise the length of the entity bytes.
     *
     * @param responseContext the response context
     * @param body the original entity bytes
     * @return the declared body length in bytes
     */
    private static int declaredLength(ContainerResponseContext responseContext, byte[] body) {
        String header = responseContext.getHeaderString("Content-Length");
        if (header != null) {
            try {
                return Integer.parseInt(header.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the entity length
            }
        }
        return body.length;
    }

    /**
     * Defines the explicit {@code Content-Length} behavior of the assault.
     * <p>
     * {@link ResponseBodyMode#TRUNCATE} keeps the response well-framed: the declared length is updated to match the
     * truncated payload, and the corruption is purely at the content level (a client parsing the payload fails).
     * {@link ResponseBodyMode#INFLATE} deliberately advertises the <em>original</em>, smaller length while the emitted
     * payload is larger, so the declared {@code Content-Length} and the actual bytes on the wire diverge -- exactly the
     * condition length-validating proxies and clients must handle.
     *
     * @param responseContext the response context
     * @param mode the active transformation mode
     * @param originalLength the length advertised before the transformation
     * @param transformedLength the length of the transformed payload
     */
    private static void applyContentLength(ContainerResponseContext responseContext, ResponseBodyMode mode,
            int originalLength, int transformedLength) {
        int declared = mode == ResponseBodyMode.INFLATE ? originalLength : transformedLength;
        responseContext.getHeaders().putSingle("Content-Length", Integer.toString(declared));
    }

    /**
     * Extracts the response entity as raw bytes when it is a bufferable type ({@link String}, {@code byte[]} or
     * {@link CharSequence}); streaming or resource backed entities are left untouched.
     *
     * @param entity the response entity
     * @return the UTF-8 bytes of the entity, or {@code null} when the entity type is not supported
     */
    private static byte[] toBytes(Object entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof byte[] bytes) {
            return bytes;
        }
        if (entity instanceof String text) {
            return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (entity instanceof CharSequence sequence) {
            return sequence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Replaces the response entity with the transformed payload, keeping the original media type.
     * <p>
     * The transformed value is always exposed as a {@code byte[]} so the exact bytes produced by
     * {@link ResponseBodyTransformer} reach the wire unchanged. Rebuilding a {@link String} would let the UTF-8
     * encoder replace a truncated multibyte character with a replacement character and change the byte length, making
     * the configured percentage unreliable for non-ASCII payloads.
     *
     * @param responseContext the response context to update
     * @param bytes the transformed payload
     */
    private static void setEntity(ContainerResponseContext responseContext, byte[] bytes) {
        responseContext.setEntity(bytes, null, responseContext.getMediaType());
    }

    private boolean isTargetEligible() {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return false;
        }

        Class<?> declaringClass = method.getDeclaringClass();
        String packageName = declaringClass.getPackage().getName();

        if (config.target().excludePackages().isPresent()) {
            for (String excluded : config.target().excludePackages().get()) {
                if (packageName.startsWith(excluded)) {
                    return false;
                }
            }
        }

        if (config.target().includePackages().isPresent() && config.target().includePackages().get().length > 0) {
            boolean included = false;
            for (String includedPkg : config.target().includePackages().get()) {
                if (packageName.startsWith(includedPkg)) {
                    included = true;
                    break;
                }
            }
            if (!included) {
                return false;
            }
        }

        if (config.target().excludeAnnotations().isPresent()) {
            Set<java.lang.annotation.Annotation> annotations = Set.of(method.getAnnotations());
            for (String annotationName : config.target().excludeAnnotations().get()) {
                for (java.lang.annotation.Annotation ann : annotations) {
                    if (ann.annotationType().getName().equals(annotationName)) {
                        return false;
                    }
                }
                for (java.lang.annotation.Annotation ann : declaringClass.getAnnotations()) {
                    if (ann.annotationType().getName().equals(annotationName)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private String describeMethod() {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return "unknown";
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
