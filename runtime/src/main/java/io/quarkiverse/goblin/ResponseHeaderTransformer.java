package io.quarkiverse.goblin;

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
                    headers.putSingle(name, rule.value());
                    record(engine, methodName, name, "set");
                }
                case REMOVE -> {
                    if (responseContext.getHeaderString(name) != null) {
                        headers.remove(name);
                        record(engine, methodName, name, "remove");
                    }
                }
            }
        }
    }

    private static void record(AssaultEngine engine, String methodName, String headerName, String action) {
        LOG.debugf("Goblin: %s response header %s on %s", action, headerName, methodName);
        engine.recordAssault(methodName, "response-header-" + action + ":" + headerName);
    }
}