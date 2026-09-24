package io.quarkiverse.goblin;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Request-context property holding the target-level gate decision made in the request phase, so the response phase
     * applies response body/header assaults to the exact same selection instead of rolling the dice a second time.
     */
    static final String GATED_PROPERTY = "io.quarkiverse.goblin.gated";

    @Inject
    Instance<Assault> assaults;

    @Inject
    AssaultEngine engine;

    @Inject
    ResourceInfo resourceInfo;

    @Inject
    GoblinTargetingConfig targeting;

    /**
     * Targeting eligibility per resource method, computed on first use: the rules are fixed at build time, so the
     * reflection on annotations is paid once instead of on every request.
     */
    private final Map<Method, Boolean> eligibility = new ConcurrentHashMap<>();

    private volatile TargetRules targetRules;

    /**
     * The assault chain sorted by {@link Assault#order()}. The set of assault beans is fixed at build time, so it is
     * sorted once, lazily, instead of on every request.
     */
    private volatile List<Assault> sortedAssaults;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // never let a decision left over on this pooled thread (e.g. a response filter that never ran) leak into this
        // request, even when chaos is currently inactive
        ChaosRequestContext.clear();
        if (!engine.isActive()) {
            return;
        }
        ChaosLayer assaultLayer = engine.resolveAssaultLayer();
        requestContext.setProperty(GATED_PROPERTY, assaultLayer == ChaosLayer.HTTP_IN);
        ChaosRequestContext.setAssaultLayer(assaultLayer);
        if (LOG.isDebugEnabled()) {
            MutableAssaultConfig cfg = engine.configSnapshot();
            if (cfg != null) {
                LOG.debugf("Goblin: request resolved to %s layer (level %d) for %s",
                        assaultLayer, cfg.getTargetLevel(), describeMethod());
            }
        }
        if (assaultLayer != ChaosLayer.HTTP_IN) {
            return;
        }
        if (!isTargetEligible()) {
            return;
        }

        String methodName = describeMethod();
        MutableAssaultConfig cfg = engine.configSnapshot();
        AssaultContext context = new AssaultContext(requestContext, cfg, engine, methodName);

        for (Assault assault : sortedAssaults()) {
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
        ChaosRequestContext.clear();
        if (!engine.isActive()) {
            return;
        }
        MutableAssaultConfig cfg = engine.configSnapshot();
        if (cfg == null) {
            return;
        }
        if (!isGated(requestContext)) {
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
     * Returns the target-level gate decision for this request, reusing the one made during the request phase so the
     * response phase does not draw a second random number (which would make response body/header assaults fire
     * independently of the request-phase selection). When no request-phase decision is available -- for example a
     * response filter running without a matching request filter -- the gate is evaluated once here.
     *
     * @param requestContext the request context carried over from the request phase
     * @return {@code true} when the request was selected for assault
     */
    private boolean isGated(ContainerRequestContext requestContext) {
        Object gated = requestContext.getProperty(GATED_PROPERTY);
        if (gated instanceof Boolean decision) {
            return decision;
        }
        return engine.shouldAssault();
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

    private List<Assault> sortedAssaults() {
        List<Assault> current = sortedAssaults;
        if (current == null) {
            current = assaults.stream().sorted(Comparator.comparingInt(Assault::order)).toList();
            sortedAssaults = current;
        }
        return current;
    }

    private boolean isTargetEligible() {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return false;
        }
        return eligibility.computeIfAbsent(method, this::computeEligibility);
    }

    /**
     * Applies the targeting rules to a resource method: its package, then the annotations of the method and of its
     * declaring class. Computed once per resource method (the rules are fixed at build time).
     *
     * @param method the resource method
     * @return {@code true} when the method may be assaulted
     */
    private boolean computeEligibility(Method method) {
        TargetRules rules = targetRules();
        Class<?> declaringClass = method.getDeclaringClass();
        if (!rules.isPackageTargeted(declaringClass.getPackageName())) {
            return false;
        }
        return !rules.isExcludedBy(annotationNames(method.getAnnotations()))
                && !rules.isExcludedBy(annotationNames(declaringClass.getAnnotations()));
    }

    private TargetRules targetRules() {
        TargetRules current = targetRules;
        if (current == null) {
            current = TargetRules.of(targeting);
            targetRules = current;
        }
        return current;
    }

    private static List<String> annotationNames(Annotation[] annotations) {
        return Arrays.stream(annotations).map(annotation -> annotation.annotationType().getName()).toList();
    }

    private String describeMethod() {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return "unknown";
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
