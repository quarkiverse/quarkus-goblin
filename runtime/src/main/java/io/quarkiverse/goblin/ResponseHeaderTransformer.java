package io.quarkiverse.goblin;

import java.util.ArrayList;
import java.util.Map;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.logging.Logger;

/**
 * Applies the configured response header injection rules to an emitted response.
 * <p>
 * The rules are read from {@link MutableAssaultConfig#getResponseHeaders()}, keyed by header name. Depending on the
 * {@link ResponseHeaderAction}: {@code SET} forces the header to be present with the configured value (replacing any
 * existing value, or adding it when absent), and {@code REMOVE} deletes the header when present. Every applied rule is
 * recorded in the assault history with a type of the form {@code response-header-<action>:<headerName>}.
 * <p>
 * HTTP header names are case-insensitive, so existing headers are matched ignoring case: a {@code SET} replaces a
 * header emitted with any casing (instead of adding a duplicate), and a {@code REMOVE} deletes it regardless of the
 * casing the application used.
 */
public final class ResponseHeaderTransformer {

    private static final Logger LOG = Logger.getLogger(ResponseHeaderTransformer.class);

    private ResponseHeaderTransformer() {
    }

    /**
     * Applies every configured header rule to the response and records each fired rule in the assault history.
     *
     * @param responseContext the response context whose headers are manipulated
     * @param config the current mutable assault configuration holding the rules
     * @param engine the engine recording the fired rules
     * @param methodName the targeted endpoint method, e.g. {@code "com.example.ApiResource.hello"}
     */
    public static void apply(ContainerResponseContext responseContext, MutableAssaultConfig config, AssaultEngine engine,
            String methodName) {
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();
        for (Map.Entry<String, MutableAssaultConfig.HeaderRule> entry : config.getResponseHeaders().entrySet()) {
            String name = entry.getKey();
            MutableAssaultConfig.HeaderRule rule = entry.getValue();
            switch (rule.action()) {
                case SET -> {
                    if (!MutableAssaultConfig.isValidResponseHeaderValue(rule.value())) {
                        LOG.warnf("Goblin: skipping response header '%s' on %s: the configured value cannot be emitted "
                                + "as an HTTP header", name, methodName);
                        continue;
                    }
                    removeIgnoringCase(headers, name);
                    headers.putSingle(name, rule.value());
                    record(engine, methodName, name, "set");
                }
                case REMOVE -> {
                    if (removeIgnoringCase(headers, name)) {
                        record(engine, methodName, name, "remove");
                    }
                }
            }
        }
    }

    /**
     * Removes every header whose name matches the given name ignoring case, returning whether at least one was found.
     *
     * @param headers the response headers
     * @param name the header name to look for, matched case-insensitively
     * @return {@code true} when at least one header was removed
     */
    private static boolean removeIgnoringCase(MultivaluedMap<String, Object> headers, String name) {
        boolean removed = false;
        for (String existing : new ArrayList<>(headers.keySet())) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                headers.remove(existing);
                removed = true;
            }
        }
        return removed;
    }

    private static void record(AssaultEngine engine, String methodName, String headerName, String action) {
        LOG.debugf("Goblin: %s response header %s on %s", action, headerName, methodName);
        engine.recordAssault(methodName, "response-header-" + action + ":" + headerName);
    }
}