package io.quarkiverse.goblin;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;

/**
 * Carries the decided assault layer (the deepest armed layer whose target-level draw passed) for the current
 * pseudo-request, so every hook on the path -- the JAX-RS filters and the service interceptor -- agrees on which layer
 * fires and the shallower layers pass through.
 * <p>
 * The decision is resolved once per request by {@link AssaultEngine#resolveAssaultLayer()} inside the inbound filter
 * ({@link GoblinChaosFilter}), which also clears any stale state first, and cleared again when the response filter
 * runs. Backed by a {@link ThreadLocal}: this only holds when the inbound filter, the dispatched business code and the
 * response filter run on the same thread, i.e. the synchronous (blocking, worker-thread) execution model. The decision
 * is <em>not</em> propagated to other threads: a service method run asynchronously ({@code @Asynchronous},
 * {@code ManagedExecutor}, reactive continuations) is never service-assaulted. Asynchronous / non-blocking paths are
 * out of scope for now (see issue #54).
 * <p>
 * The decision is bound to the CDI request context active when it was made: a decision read from another request
 * context (or from none) is stale -- typically left on a pooled worker thread by an HTTP request whose response filter
 * never ran because an exception escaped the resource -- and is discarded, so a later message consumer or scheduled
 * job on the same thread is never assaulted by an unrelated decision.
 * <p>
 * Besides the decision, the context tracks the nesting depth of intercepted service calls and whether the armed layer
 * already fired, so that only the outermost intercepted call is assaulted (nested bean-to-bean calls never
 * multiply the fault) and each further outermost call -- typically a {@code @Retry} attempt -- draws the target level
 * again.
 */
public final class ChaosRequestContext {

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private ChaosRequestContext() {
    }

    private static final class State {
        Object owner;
        ChaosLayer layer;
        int serviceDepth;
        boolean fired;
    }

    /**
     * Arms the given layer for the current request, or {@code null} when no armed layer passed the level gate. Resets
     * the service nesting state.
     *
     * @param assaultLayer the resolved assault layer, or {@code null}
     */
    public static void setAssaultLayer(ChaosLayer assaultLayer) {
        if (assaultLayer == null) {
            STATE.remove();
            return;
        }
        State state = new State();
        state.owner = currentOwner();
        state.layer = assaultLayer;
        STATE.set(state);
    }

    /**
     * Returns the state of the current pseudo-request, discarding a stale one bound to another request context.
     *
     * @return the current state, or {@code null}
     */
    private static State current() {
        State state = STATE.get();
        if (state != null && state.owner != currentOwner()) {
            STATE.remove();
            return null;
        }
        return state;
    }

    /**
     * @return the state object of the active CDI request context, or {@code null} when none is active (or outside a
     *         Quarkus runtime, e.g. plain unit tests)
     */
    private static Object currentOwner() {
        ArcContainer container = Arc.container();
        if (container == null) {
            return null;
        }
        ManagedContext requestContext = container.requestContext();
        return requestContext.isActive() ? requestContext.getState() : null;
    }

    /**
     * @return the resolved assault layer for the current request, or {@code null} when none was selected
     */
    public static ChaosLayer assaultLayer() {
        State state = current();
        return state != null ? state.layer : null;
    }

    /**
     * @param layer the layer to test
     * @return {@code true} when the given layer is the armed layer for the current request
     */
    public static boolean is(ChaosLayer layer) {
        return assaultLayer() == layer;
    }

    /**
     * @return whether the service layer was selected as the armed layer for the current request
     */
    public static boolean isServiceArmed() {
        return assaultLayer() == ChaosLayer.SERVICE;
    }

    /**
     * Enters an intercepted service call. Must be paired with {@link #exitService()} in a {@code finally} block.
     *
     * @return {@code true} when this is the outermost intercepted service call of the request
     */
    public static boolean enterService() {
        State state = current();
        if (state == null) {
            return false;
        }
        return state.serviceDepth++ == 0;
    }

    /**
     * Leaves an intercepted service call entered with {@link #enterService()}.
     */
    public static void exitService() {
        State state = current();
        if (state != null && state.serviceDepth > 0) {
            state.serviceDepth--;
        }
    }

    /**
     * Marks the armed layer as fired for the current request.
     *
     * @return {@code true} when the armed layer had already fired earlier in this request (the caller must then draw
     *         the target level again instead of reusing the per-request decision)
     */
    public static boolean markFired() {
        State state = current();
        if (state == null) {
            return false;
        }
        boolean alreadyFired = state.fired;
        state.fired = true;
        return alreadyFired;
    }

    /**
     * Unbinds the current thread. Must be called after the request completes so a pooled thread never leaks a stale
     * decision into an unrelated (e.g. scheduled, non-HTTP) invocation.
     */
    public static void clear() {
        STATE.remove();
    }
}
