package io.quarkiverse.goblin;

/**
 * Carries the decided assault layer (the deepest armed layer whose target-level draw passed) for the current
 * pseudo-request, so every hook on the path -- the JAX-RS filters and the service interceptor -- agrees on which layer
 * fires and the shallower layers pass through.
 * <p>
 * The decision is resolved once per request by {@link AssaultEngine#resolveAssaultLayer()} inside the inbound filter
 * ({@link GoblinChaosFilter}) and cleared when the response filter runs. Backed by a {@link ThreadLocal}: the inbound
 * filter, the dispatched business code and the response filter run on the same thread for the synchronous execution
 * model supported today. Concurrent requests on the same event-loop thread never interleave because the whole chain
 * runs inline on one thread. Asynchronous / non-blocking paths are out of scope for now (see issue #54).
 */
public final class ChaosRequestContext {

    private static final ThreadLocal<ChaosLayer> ASSAULT_LAYER = new ThreadLocal<>();

    private ChaosRequestContext() {
    }

    /**
     * Arms the given layer for the current request, or {@code null} when no armed layer passed the level gate.
     *
     * @param assaultLayer the resolved assault layer, or {@code null}
     */
    public static void setAssaultLayer(ChaosLayer assaultLayer) {
        ASSAULT_LAYER.set(assaultLayer);
    }

    /**
     * @return the resolved assault layer for the current request, or {@code null} when none was selected
     */
    public static ChaosLayer assaultLayer() {
        return ASSAULT_LAYER.get();
    }

    /**
     * @param layer the layer to test
     * @return {@code true} when the given layer is the armed layer for the current request
     */
    public static boolean is(ChaosLayer layer) {
        return ASSAULT_LAYER.get() == layer;
    }

    /**
     * @return whether the service layer was selected as the armed layer for the current request
     */
    public static boolean isServiceArmed() {
        return ASSAULT_LAYER.get() == ChaosLayer.SERVICE;
    }

    /**
     * Unbinds the current thread. Must be called after the request completes so a pooled thread never leaks a stale
     * decision into an unrelated (e.g. scheduled, non-HTTP) invocation.
     */
    public static void clear() {
        ASSAULT_LAYER.remove();
    }
}