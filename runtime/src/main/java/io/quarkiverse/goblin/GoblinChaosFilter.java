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
