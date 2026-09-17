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
        if (cfg == null || !cfg.isResponseBodyEnabled()) {
            return;
        }
        if (!isTargetEligible()) {
            return;
        }
        byte[] body = toBytes(responseContext.getEntity());
        if (body == null) {
            return;
        }
        byte[] transformed = ResponseBodyTransformer.transform(body, cfg.getResponseBodyMode(),
                cfg.getResponseBodyPercentage());
        setEntity(responseContext, transformed);
        LOG.debugf("Goblin: response body %s (100%% -> %d%%) on %s",
                cfg.getResponseBodyMode().name().toLowerCase(), cfg.getResponseBodyPercentage(), describeMethod());
        engine.recordAssault(describeMethod(), "response-body-" + cfg.getResponseBodyMode().name().toLowerCase());
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
     * Replaces the response entity with the transformed payload, keeping the original media type. Strings are restored
     * as {@link String}, byte arrays remain byte arrays.
     *
     * @param responseContext the response context to update
     * @param bytes the transformed payload
     */
    private static void setEntity(ContainerResponseContext responseContext, byte[] bytes) {
        Object entity = responseContext.getEntity();
        if (entity instanceof byte[]) {
            responseContext.setEntity(bytes, null, responseContext.getMediaType());
        } else {
            responseContext.setEntity(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), null,
                    responseContext.getMediaType());
        }
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
