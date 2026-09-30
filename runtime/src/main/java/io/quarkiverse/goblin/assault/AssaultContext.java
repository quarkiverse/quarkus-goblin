package io.quarkiverse.goblin.assault;

import java.lang.reflect.Method;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Context passed to an {@link Assault} when it is applied to a request.
 * <p>
 * Carries everything an assault needs to inspect the request, read the current configuration, record the assault in
 * the history, and know which endpoint method is being targeted. The request context may be {@code null} in unit tests
 * that do not exercise a real JAX-RS pipeline.
 */
public class AssaultContext {

    private final ContainerRequestContext requestContext;
    private final MutableAssaultConfig config;
    private final AssaultEngine engine;
    private final String methodName;
    private final Method resourceMethod;
    private final Class<?> resourceClass;

    /**
     * Creates a new assault context.
     *
     * @param requestContext the JAX-RS request context, or {@code null} outside a real pipeline
     * @param config the current mutable assault configuration
     * @param engine the engine that records assaults in the history
     * @param methodName the targeted endpoint method, e.g. {@code "com.example.ApiResource.hello"}
     */
    public AssaultContext(ContainerRequestContext requestContext, MutableAssaultConfig config, AssaultEngine engine,
            String methodName) {
        this(requestContext, config, engine, methodName, null, null);
    }

    /**
     * Creates a new assault context carrying the targeted resource method and class, so abort assaults can match the
     * declared {@code @Produces} contract (issue #69).
     *
     * @param requestContext the JAX-RS request context, or {@code null} outside a real pipeline
     * @param config the current mutable assault configuration
     * @param engine the engine that records assaults in the history
     * @param methodName the targeted endpoint method, e.g. {@code "com.example.ApiResource.hello"}
     * @param resourceMethod the targeted resource method, or {@code null} when unknown
     * @param resourceClass the targeted resource class, or {@code null} when unknown
     */
    public AssaultContext(ContainerRequestContext requestContext, MutableAssaultConfig config, AssaultEngine engine,
            String methodName, Method resourceMethod, Class<?> resourceClass) {
        this.requestContext = requestContext;
        this.config = config;
        this.engine = engine;
        this.methodName = methodName;
        this.resourceMethod = resourceMethod;
        this.resourceClass = resourceClass;
    }

    /**
     * @return the JAX-RS request context, or {@code null} in unit tests
     */
    public ContainerRequestContext getRequestContext() {
        return requestContext;
    }

    /**
     * @return the current mutable assault configuration
     */
    public MutableAssaultConfig getConfig() {
        return config;
    }

    /**
     * @return the engine used to record assaults in the history
     */
    public AssaultEngine getEngine() {
        return engine;
    }

    /**
     * @return the targeted endpoint method
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * @return the targeted resource method, or {@code null} when unknown (e.g. unit tests)
     */
    public Method getResourceMethod() {
        return resourceMethod;
    }

    /**
     * @return the targeted resource class, or {@code null} when unknown
     */
    public Class<?> getResourceClass() {
        return resourceClass;
    }
}