package io.quarkiverse.goblin.assault;

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
        this.requestContext = requestContext;
        this.config = config;
        this.engine = engine;
        this.methodName = methodName;
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
}