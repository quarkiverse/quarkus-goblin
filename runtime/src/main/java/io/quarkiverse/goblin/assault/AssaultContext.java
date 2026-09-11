package io.quarkiverse.goblin.assault;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;

public class AssaultContext {

    private final ContainerRequestContext requestContext;
    private final MutableAssaultConfig config;
    private final AssaultEngine engine;
    private final String methodName;

    public AssaultContext(ContainerRequestContext requestContext, MutableAssaultConfig config, AssaultEngine engine,
            String methodName) {
        this.requestContext = requestContext;
        this.config = config;
        this.engine = engine;
        this.methodName = methodName;
    }

    public ContainerRequestContext getRequestContext() {
        return requestContext;
    }

    public MutableAssaultConfig getConfig() {
        return config;
    }

    public AssaultEngine getEngine() {
        return engine;
    }

    public String getMethodName() {
        return methodName;
    }
}