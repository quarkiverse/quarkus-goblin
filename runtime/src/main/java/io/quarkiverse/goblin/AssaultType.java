package io.quarkiverse.goblin;

/**
 * The kind of fault an assault injects. Selects the assault applied at startup ({@code quarkus.goblin.assault.type}),
 * classifies each {@link AssaultEngine.AssaultRecord#assaultType() recorded assault}, and is the type a custom
 * {@code Assault} declares.
 * <p>
 * Part of the public API covered by semantic versioning. Constants may be added in a minor version: keep a
 * {@code default} branch when you {@code switch} on this enum.
 */
public enum AssaultType {

    /** Delays the request, call or message by a random duration within the configured latency range. */
    LATENCY,

    /** Throws the configured exception class, with the configured message. */
    EXCEPTION,

    /** Answers the inbound request with the configured HTTP status code and message, without reaching the endpoint. */
    HTTP_STATUS,

    /** Answers the inbound request with a 503, as a failing downstream dependency would. */
    DEPENDENCY_DEGRADATION,

    /** Truncates or inflates the body of the response, see {@link ResponseBodyMode}. */
    RESPONSE_BODY,

    /** Sets or removes headers of the response, following the configured rules, see {@link ResponseHeaderAction}. */
    RESPONSE_HEADER
}
