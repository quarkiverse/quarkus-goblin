package io.quarkiverse.goblin;

/**
 * The architectural layers a request flows through, ordered from the deepest to the shallowest so the chaos engine can
 * resolve its target "ascending" (from the bottom of the stack toward REST). Each layer can be armed independently in the
 * Dev UI; on every request the engine rolls the target-level gate for each armed layer and the deepest layer whose draw
 * passes becomes the armed layer for that request.
 * <p>
 * Layer order is significant and must stay ascending: {@link #DATABASE} {@link #MESSAGING} {@link #SERVICE}
 * {@link #HTTP_OUT} {@link #HTTP_IN}.
 */
public enum ChaosLayer {

    /**
     * Persistence layer (Hibernate / Agroal). Phase 2 of issue #54; not yet backed by an assault hook.
     */
    DATABASE,

    /**
     * Messaging layer (Reactive Messaging / event bus). Phase 3 of issue #54; not yet backed by an assault hook.
     */
    MESSAGING,

    /**
     * Service layer: CDI-interceptor-based latency and exception assaults on application beans, placed inside the
     * MicroProfile Fault Tolerance machinery so resilience mechanisms are exercised. Backed by
     * {@link io.quarkiverse.goblin.service.GoblinServiceInterceptor}.
     */
    SERVICE,

    /**
     * Outbound HTTP calls (REST Client / WebClient). Unlike the other layers the decision is per call, not per request:
     * each outgoing call independently rolls its own target-level gate.
     */
    HTTP_OUT,

    /**
     * Inbound REST endpoint layer (server-side latency, exception, HTTP status, dependency degradation, response body and
     * header assaults). The shallowest layer: any deeper winner makes the inbound filters pass through.
     */
    HTTP_IN;
}