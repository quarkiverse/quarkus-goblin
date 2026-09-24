package io.quarkiverse.goblin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Handles persistence of {@link MutableAssaultConfig} to disk, so Dev UI changes survive restarts.
 * <p>
 * The state is written as a structured JSON document (Vert.x JSON): booleans and numbers as such, the response header
 * rules as a nested object and the armed layers as an array. The file is user-editable, so reading is tolerant: values
 * may also be quoted strings, and the legacy flat format of earlier versions (header rules encoded as a JSON string,
 * layers as a comma-separated string) is still accepted. Missing fields fall back to their defaults; a value that
 * cannot be parsed makes {@link #load()} fall back to {@code application.properties}.
 * <p>
 * By default {@link #save(MutableAssaultConfig)} and {@link #load()} use a fixed {@code .goblin-state.json} file in the
 * process working directory. Tests may point persistence at a temporary file via
 * {@link #overrideStateFile(String)}.
 */
public final class GoblinStatePersistence {

    private static final Logger LOG = Logger.getLogger(GoblinStatePersistence.class);
    private static final String STATE_FILE = ".goblin-state.json";

    private static volatile String stateFile = STATE_FILE;

    private GoblinStatePersistence() {
    }

    /**
     * Redefines the state file location. Intended for tests so the fixed working-directory file is never written; pass
     * {@code null} to restore the default.
     *
     * @param path the new state file path, or {@code null} to restore the default {@value #STATE_FILE}
     */
    static void overrideStateFile(String path) {
        stateFile = path != null ? path : STATE_FILE;
    }

    /**
     * Persists the configuration. The JSON is written to a temporary sibling file then moved over the state file, so
     * a crash or a concurrent save never leaves a truncated file behind; concurrent saves are serialised.
     *
     * @param config the configuration to persist
     */
    public static synchronized void save(MutableAssaultConfig config) {
        Path target = Path.of(stateFile).toAbsolutePath();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, toJson(config).encodePrettily());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warnf("Failed to persist %s: %s", stateFile, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort: a leftover temporary file is overwritten by the next save
            }
        }
    }

    /**
     * Loads the persisted configuration.
     *
     * @return the restored configuration, or {@code null} when there is no state file or it cannot be read (the caller
     *         then falls back to {@code application.properties})
     */
    public static MutableAssaultConfig load() {
        Path path = Path.of(stateFile);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return fromJson(Files.readString(path));
        } catch (IOException | RuntimeException e) {
            // a corrupted or hand-edited file (not JSON, a non-numeric latency...) must never prevent the application
            // from starting: fall back to the static configuration
            LOG.warnf("Failed to load %s, falling back to application.properties: %s",
                    stateFile, e.getMessage());
            return null;
        }
    }

    /**
     * Serialises the given configuration for persistence. The enabled/active flag is deliberately not persisted: it
     * always comes from {@code quarkus.goblin.enabled}.
     *
     * @param config the configuration to persist
     * @return the JSON document
     */
    static JsonObject toJson(MutableAssaultConfig config) {
        JsonObject headers = new JsonObject();
        config.getResponseHeaders().forEach((name, rule) -> headers.put(name, new JsonObject()
                .put("action", rule.action().name())
                .put("value", rule.value())));
        JsonArray layers = new JsonArray();
        config.getLayers().forEach(layer -> layers.add(layer.name()));
        long[] latency = config.getLatencyRange();
        return new JsonObject()
                .put("profile", config.getProfile().name())
                .put("latencyEnabled", config.isLatencyEnabled())
                .put("exceptionEnabled", config.isExceptionEnabled())
                .put("httpStatusEnabled", config.isHttpStatusEnabled())
                .put("dependencyDegradationEnabled", config.isDependencyDegradationEnabled())
                .put("clientLatencyEnabled", config.isClientLatencyEnabled())
                .put("clientExceptionEnabled", config.isClientExceptionEnabled())
                .put("responseBodyEnabled", config.isResponseBodyEnabled())
                .put("responseBodyMode", config.getResponseBodyMode().name())
                .put("responseBodyPercentage", config.getResponseBodyPercentage())
                .put("responseHeaderEnabled", config.isResponseHeaderEnabled())
                .put("responseHeaders", headers)
                .put("latencyMinMs", latency[0])
                .put("latencyMaxMs", latency[1])
                .put("exceptionType", config.getExceptionType())
                .put("exceptionMessage", config.getExceptionMessage())
                .put("httpStatusCode", config.getHttpStatusCode())
                .put("httpStatusMessage", config.getHttpStatusMessage())
                .put("targetLevel", config.getTargetLevel())
                .put("layers", layers);
    }

    /**
     * Rebuilds a configuration from its JSON representation, restoring the profile label without applying its defaults.
     *
     * @param json the persisted JSON
     * @return the reconstructed configuration with missing fields defaulted
     * @throws io.vertx.core.json.DecodeException when the document is not a JSON object
     * @throws NumberFormatException when a numeric field cannot be parsed
     */
    static MutableAssaultConfig fromJson(String json) {
        State state = new State(new JsonObject(escapeControlCharactersInStrings(json)));
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.restoreProfile(state.enumValue("profile", AssaultProfile.class, AssaultProfile.NONE));
        config.setLatencyEnabled(state.bool("latencyEnabled", true));
        config.setExceptionEnabled(state.bool("exceptionEnabled", false));
        config.setHttpStatusEnabled(state.bool("httpStatusEnabled", false));
        config.setDependencyDegradationEnabled(state.bool("dependencyDegradationEnabled", false));
        config.setClientLatencyEnabled(state.bool("clientLatencyEnabled", false));
        config.setClientExceptionEnabled(state.bool("clientExceptionEnabled", false));
        config.setResponseBodyMode(state.enumValue("responseBodyMode", ResponseBodyMode.class, ResponseBodyMode.TRUNCATE));
        config.setResponseBodyEnabled(state.bool("responseBodyEnabled", false));
        config.setResponseBodyPercentage((int) state.number("responseBodyPercentage", 50));
        config.setResponseHeaderEnabled(state.bool("responseHeaderEnabled", false));
        applyResponseHeaders(state.json.getValue("responseHeaders"), config);
        config.setLatencyMinMs(state.number("latencyMinMs", 100));
        config.setLatencyMaxMs(state.number("latencyMaxMs", 5000));
        config.setExceptionType(state.text("exceptionType", "java.lang.RuntimeException"));
        config.setExceptionMessage(state.text("exceptionMessage", "Goblin chaos: simulated exception"));
        config.setHttpStatusCode((int) state.number("httpStatusCode", 503));
        config.setHttpStatusMessage(state.text("httpStatusMessage", "Service Unavailable (Goblin chaos)"));
        config.setTargetLevel((int) state.number("targetLevel", 100));
        config.setLayers(parseLayers(state.json.getValue("layers"), state));
        if (!state.defaulted.isEmpty()) {
            LOG.infof("Restored missing fields from defaults: %s", String.join(", ", state.defaulted));
        }
        return config;
    }

    /**
     * Escapes the raw control characters (line feeds, tabs...) found <em>inside</em> JSON string literals. Earlier versions
     * wrote the response header rules as pretty-printed JSON embedded in a string without escaping its line breaks,
     * which strict JSON parsers reject; control characters outside strings are whitespace and are kept.
     *
     * @param json the persisted text
     * @return the text with every control character inside a string literal escaped
     */
    static String escapeControlCharactersInStrings(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString && !escaped && c < 0x20) {
                switch (c) {
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(String.format("\\u%04x", (int) c));
                }
                continue;
            }
            out.append(c);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return out.toString();
    }

    /**
     * Tolerant, typed access to the persisted document, collecting the keys that fell back to their default.
     */
    private static final class State {

        final JsonObject json;
        final List<String> defaulted = new ArrayList<>();

        State(JsonObject json) {
            this.json = json;
        }

        /**
         * @return the raw value, or {@code null} (and the key recorded as defaulted) when missing or blank
         */
        private Object raw(String key) {
            Object value = json.getValue(key);
            if (value == null || (value instanceof String text && text.isBlank())) {
                defaulted.add(key);
                return null;
            }
            return value;
        }

        boolean bool(String key, boolean defaultValue) {
            Object value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            return value instanceof Boolean flag ? flag : Boolean.parseBoolean(value.toString().trim());
        }

        long number(String key, long defaultValue) {
            Object value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString().trim());
        }

        String text(String key, String defaultValue) {
            Object value = raw(key);
            return value == null ? defaultValue : value.toString();
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue) {
            Object value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            return Enums.parse(type, value.toString()).orElseGet(() -> {
                LOG.warnf("Invalid %s '%s' in state file, defaulting to %s", key, value, defaultValue);
                return defaultValue;
            });
        }
    }

    /**
     * Restores the armed layers from a JSON array, or from the legacy comma-separated string, skipping unknown labels
     * with a warning and defaulting to {@code HTTP_IN,HTTP_OUT} when nothing valid remains.
     *
     * @param raw the persisted value, or {@code null}
     * @param state the document, used to record a defaulted key
     * @return the restored layer set
     */
    private static Set<ChaosLayer> parseLayers(Object raw, State state) {
        List<String> names = new ArrayList<>();
        if (raw instanceof JsonArray array) {
            array.forEach(item -> names.add(String.valueOf(item)));
        } else if (raw instanceof String legacy) {
            names.addAll(List.of(legacy.split(",")));
        }
        Set<ChaosLayer> result = EnumSet.noneOf(ChaosLayer.class);
        for (String name : names) {
            if (name.isBlank()) {
                continue;
            }
            Enums.parse(ChaosLayer.class, name).ifPresentOrElse(result::add,
                    () -> LOG.warnf("Invalid chaos layer '%s' in state file, skipping it", name.trim()));
        }
        if (result.isEmpty()) {
            state.defaulted.add("layers");
            return EnumSet.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT);
        }
        return result;
    }

    /**
     * Restores the configured header rules. The state file is user-editable, so every entry is validated before being
     * restored: entries that are not a nested object, whose action is missing or unknown, or whose value cannot be
     * emitted as an HTTP header are skipped with a warning rather than turned into an unintended injection rule.
     * Accepts the nested object written today and the legacy JSON-in-a-string encoding.
     *
     * @param raw the persisted value, or {@code null} when the state file predates the feature
     * @param config the configuration to populate
     */
    private static void applyResponseHeaders(Object raw, MutableAssaultConfig config) {
        JsonObject rules = asObject(raw);
        if (rules == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : rules) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                LOG.warnf("Skipping response header rule with a blank name in the state file");
                continue;
            }
            JsonObject rule = asObject(entry.getValue());
            if (rule == null) {
                LOG.warnf("Skipping malformed response header rule for '%s' in the state file: expected a nested object",
                        name);
                continue;
            }
            ResponseHeaderAction action = ResponseHeaderAction.parse(rule.getString("action")).orElse(null);
            if (action == null) {
                LOG.warnf("Skipping response header rule for '%s' in the state file: missing or invalid action", name);
                continue;
            }
            String value = rule.getValue("value") != null ? rule.getValue("value").toString() : "";
            if (!MutableAssaultConfig.isValidResponseHeaderValue(value)) {
                LOG.warnf("Skipping response header rule for '%s' in the state file: the value cannot be emitted as an "
                        + "HTTP header", name);
                continue;
            }
            try {
                config.setResponseHeader(name, action, value);
                LOG.debugf("Goblin: restored response header rule '%s' %s value='%s' from state file", name, action, value);
            } catch (IllegalArgumentException e) {
                LOG.warnf("Skipping invalid response header rule for '%s' in the state file: %s", name, e.getMessage());
            }
        }
    }

    /**
     * @param value a nested object, a JSON object encoded as a string (legacy format), or anything else
     * @return the object, or {@code null} when the value is not an object
     */
    private static JsonObject asObject(Object value) {
        if (value instanceof JsonObject object) {
            return object;
        }
        if (value instanceof String text && text.strip().startsWith("{") && text.strip().endsWith("}")) {
            try {
                return new JsonObject(escapeControlCharactersInStrings(text));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
