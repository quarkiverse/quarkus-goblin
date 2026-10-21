package io.quarkiverse.goblin.assault;

import java.lang.reflect.Method;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.internal.AssaultEngineImpl;
import io.smallrye.common.annotation.Experimental;

/**
 * Context passed to an {@link Assault} when it is applied to a request by the JAX-RS filter (reactive routes never
 * call an {@code Assault} bean).
 * <p>
 * Carries everything an assault needs to inspect the request, read the current configuration, record the assault in
 * the history with {@link #record(Assault)}, and know which endpoint method is being targeted. The request context may be
 * {@code null} in unit tests
 * that do not exercise a real JAX-RS pipeline.
 * <p>
 * Experimental: custom assaults run on the inbound JAX-RS filter only, so this SPI may change in a minor version when
 * another layer supports them. The rest of the public API is stable.
 */
@Experimental("Custom assaults are bound to JAX-RS; the SPI may change once another layer supports them")
public class AssaultContext {

    private final ContainerRequestContext requestContext;
    private final AssaultConfig config;
    private final AssaultEngine engine;
    private final String methodName;
    private final Method resourceMethod;
    private final Class<?> resourceClass;

    /**
     * Creates a new assault context.
     *
     * @param requestContext the JAX-RS request context, or {@code null} outside a real pipeline
     * @param config the configuration for this request, a read-only {@link MutableAssaultConfig#snapshot() snapshot}
     * @param engine the engine provided by Goblin; {@link #record(Assault)} records only through it
     * @param methodName the targeted endpoint method, e.g. {@code "com.example.ApiResource.hello"}
     */
    public AssaultContext(ContainerRequestContext requestContext, AssaultConfig config, AssaultEngine engine,
            String methodName) {
        this(requestContext, config, engine, methodName, null, null);
    }

    /**
     * Creates a new assault context carrying the targeted resource method and class, so abort assaults can match the
     * declared {@code @Produces} contract (issue #69).
     *
     * @param requestContext the JAX-RS request context, or {@code null} outside a real pipeline
     * @param config the configuration for this request, a read-only {@link MutableAssaultConfig#snapshot() snapshot}
     * @param engine the engine provided by Goblin; {@link #record(Assault)} records only through it
     * @param methodName the targeted endpoint method, e.g. {@code "com.example.ApiResource.hello"}
     * @param resourceMethod the targeted resource method, or {@code null} when unknown
     * @param resourceClass the targeted resource class, or {@code null} when unknown
     */
    public AssaultContext(ContainerRequestContext requestContext, AssaultConfig config, AssaultEngine engine,
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
     * Returns the configuration for this request: a read-only {@link MutableAssaultConfig#snapshot() snapshot}, so every
     * read is consistent for the whole request. To change the configuration, use
     * {@link AssaultEngine#getMutableConfig()}.
     *
     * @return the configuration snapshot of this request
     */
    public AssaultConfig getConfig() {
        return config;
    }

    /**
     * @return the chaos engine
     */
    public AssaultEngine getEngine() {
        return engine;
    }

    /**
     * Records the given assault in the history against the targeted method, so it is counted, exported and reported to
     * the {@link io.quarkiverse.goblin.AssaultObserver observers} like a built-in one. Call it once the assault is
     * applied.
     * <p>
     * Recording goes through the engine Goblin provides, the one the filter passes in. With any other
     * {@link AssaultEngine} -- a mock or a stub in a unit test of a custom assault -- it does nothing; assert on what
     * the assault did to the request instead, or run the assault in a {@code @QuarkusTest} and read
     * {@link AssaultEngine#getHistory()}.
     *
     * @param assault the applied assault, whose {@link Assault#type()} and {@link Assault#recordLabel()} are recorded
     */
    public void record(Assault assault) {
        record(assault, 0);
    }

    /**
     * Records the given assault in the history, with the latency it injected. See {@link #record(Assault)}.
     *
     * @param assault the applied assault, whose {@link Assault#type()} and {@link Assault#recordLabel()} are recorded
     * @param latencyMs the injected latency in milliseconds
     */
    public void record(Assault assault, long latencyMs) {
        if (engine instanceof AssaultEngineImpl impl) {
            impl.recordAssault(assault.type(), methodName, assault.recordLabel(), latencyMs);
        }
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
