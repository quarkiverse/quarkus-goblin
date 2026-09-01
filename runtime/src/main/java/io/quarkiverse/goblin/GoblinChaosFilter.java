package io.quarkiverse.goblin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

@Provider
@ApplicationScoped
public class GoblinChaosFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(GoblinChaosFilter.class);

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

        // Apply latency first (adds delay before processing)
        if (cfg.isLatencyEnabled()) {
            LOG.debugf("Goblin: injecting latency on %s", methodName);
            long delay = engine.applyLatency();
            engine.recordAssault(methodName, "latency", delay);
        }

        // Then exception (aborts the request)
        if (cfg.isExceptionEnabled()) {
            LOG.debugf("Goblin: injecting exception on %s", methodName);
            engine.recordAssault(methodName, "exception");
            throw engine.createException();
        }

        // Then HTTP status (aborts the request)
        if (cfg.isHttpStatusEnabled()) {
            LOG.debugf("Goblin: forcing HTTP %d on %s", engine.getHttpStatus(), methodName);
            engine.recordAssault(methodName, "http-status");
            requestContext.abortWith(Response.status(engine.getHttpStatus())
                    .entity(engine.getHttpStatusMessage())
                    .build());
            return;
        }

        // Then dependency degradation (aborts the request)
        if (cfg.isDependencyDegradationEnabled()) {
            LOG.debugf("Goblin: simulating dependency degradation on %s", methodName);
            engine.recordAssault(methodName, "dependency-degradation");
            requestContext.abortWith(Response.status(503)
                    .entity("Dependency unavailable (Goblin chaos)")
                    .build());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
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
