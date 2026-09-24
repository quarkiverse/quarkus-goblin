package io.quarkiverse.goblin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

/**
 * Handles persistence of {@link MutableAssaultConfig} to disk.
 * Serializes to a flat JSON file so Dev UI changes survive restarts.
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
            Files.writeString(tmp, toJson(config));
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

    public static MutableAssaultConfig load() {
        Path path = Path.of(stateFile);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return fromJson(Files.readString(path));
        } catch (IOException | RuntimeException e) {
            // a corrupted or hand-edited file (e.g. a non-numeric latency) must never prevent the application from
            // starting: fall back to the static configuration
            LOG.warnf("Failed to load %s, falling back to application.properties: %s",
                    stateFile, e.getMessage());
            return null;
        }
    }

    /**
     * Serialises the given configuration to a flat JSON string for persistence.
     *
     * @param config the configuration to persist
     * @return the JSON representation of the configuration
     */
    private static String toJson(MutableAssaultConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profile", config.getProfile().name());
        map.put("latencyEnabled", config.isLatencyEnabled());
        map.put("exceptionEnabled", config.isExceptionEnabled());
        map.put("httpStatusEnabled", config.isHttpStatusEnabled());
        map.put("dependencyDegradationEnabled", config.isDependencyDegradationEnabled());
        map.put("clientLatencyEnabled", config.isClientLatencyEnabled());
        map.put("clientExceptionEnabled", config.isClientExceptionEnabled());
        map.put("responseBodyEnabled", config.isResponseBodyEnabled());
        map.put("responseBodyMode", config.getResponseBodyMode().name());
        map.put("responseBodyPercentage", config.getResponseBodyPercentage());
        map.put("responseHeaderEnabled", config.isResponseHeaderEnabled());
        map.put("responseHeaders", encodeResponseHeaders(config.getResponseHeaders()));
        map.put("latencyMinMs", config.getLatencyMinMs());
        map.put("latencyMaxMs", config.getLatencyMaxMs());
        map.put("exceptionType", config.getExceptionType());
        map.put("exceptionMessage", config.getExceptionMessage());
        map.put("httpStatusCode", config.getHttpStatusCode());
        map.put("httpStatusMessage", config.getHttpStatusMessage());
        map.put("targetLevel", config.getTargetLevel());
        map.put("layers", encodeLayers(config.getLayers()));
        return mapToJson(map);
    }

    /**
     * Rebuilds a configuration from its JSON representation, restoring the profile label without applying its defaults.
     *
     * @param json the persisted JSON
     * @return the reconstructed configuration with missing fields defaulted
     */
    static MutableAssaultConfig fromJson(String json) {
        Map<String, String> map = parseJson(json);
        List<String> defaulted = new ArrayList<>();
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.restoreProfile(parseProfile(resolve(map, "profile", "NONE", defaulted)));
        config.setLatencyEnabled(resolveBoolean(map, "latencyEnabled", "true", defaulted));
        config.setExceptionEnabled(resolveBoolean(map, "exceptionEnabled", "false", defaulted));
        config.setHttpStatusEnabled(resolveBoolean(map, "httpStatusEnabled", "false", defaulted));
        config.setDependencyDegradationEnabled(
                resolveBoolean(map, "dependencyDegradationEnabled", "false", defaulted));
        config.setClientLatencyEnabled(resolveBoolean(map, "clientLatencyEnabled", "false", defaulted));
        config.setClientExceptionEnabled(resolveBoolean(map, "clientExceptionEnabled", "false", defaulted));
        config.setResponseBodyMode(parseBodyMode(resolve(map, "responseBodyMode", "TRUNCATE", defaulted)));
        config.setResponseBodyEnabled(resolveBoolean(map, "responseBodyEnabled", "false", defaulted));
        config.setResponseBodyPercentage(resolveInt(map, "responseBodyPercentage", "50", defaulted));
        config.setResponseHeaderEnabled(resolveBoolean(map, "responseHeaderEnabled", "false", defaulted));
        applyResponseHeaders(map.get("responseHeaders"), config);
        config.setLatencyMinMs(resolveLong(map, "latencyMinMs", "100", defaulted));
        config.setLatencyMaxMs(resolveLong(map, "latencyMaxMs", "5000", defaulted));
        config.setExceptionType(resolve(map, "exceptionType", "java.lang.RuntimeException", defaulted));
        config.setExceptionMessage(resolve(map, "exceptionMessage", "Goblin chaos: simulated exception", defaulted));
        config.setHttpStatusCode(resolveInt(map, "httpStatusCode", "503", defaulted));
        config.setHttpStatusMessage(resolve(map, "httpStatusMessage", "Service Unavailable (Goblin chaos)", defaulted));
        config.setTargetLevel(resolveInt(map, "targetLevel", "100", defaulted));
        config.setLayers(parseLayers(resolve(map, "layers", defaultLayers(), defaulted)));
        if (!defaulted.isEmpty()) {
            LOG.infof("Restored missing fields from defaults: %s", String.join(", ", defaulted));
        }
        return config;
    }

    private static String resolve(Map<String, String> map, String key, String defaultValue, List<String> defaulted) {
        String val = map.get(key);
        if (val == null || val.isBlank()) {
            defaulted.add(key);
            return defaultValue;
        }
        return val;
    }

    /**
     * Encodes the armed layers as a comma-separated list (e.g. {@code "HTTP_IN,HTTP_OUT"}), which the flat state-file
     * parser stores as a plain quoted string.
     *
     * @param layers the armed layers
     * @return the comma-separated layer names, in ascending declaration order
     */
    private static String encodeLayers(Set<ChaosLayer> layers) {
        return layers.stream().map(ChaosLayer::name).collect(java.util.stream.Collectors.joining(","));
    }

    private static String defaultLayers() {
        return String.join(",", ChaosLayer.HTTP_IN.name(), ChaosLayer.HTTP_OUT.name());
    }

    /**
     * Restores the armed layers from a comma-separated list, skipping unknown labels with a warning and defaulting to
     * {@code HTTP_IN,HTTP_OUT} when the value is empty.
     *
     * @param encoded the persisted layer list, or {@code null}
     * @return the restored layer set
     */
    private static Set<ChaosLayer> parseLayers(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return EnumSet.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT);
        }
        Set<ChaosLayer> result = EnumSet.noneOf(ChaosLayer.class);
        for (String token : encoded.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                result.add(ChaosLayer.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid chaos layer '%s' in state file, skipping it", name);
            }
        }
        if (result.isEmpty()) {
            return EnumSet.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT);
        }
        return result;
    }

    /**
     * Resolves a boolean-encoded value, tolerating surrounding whitespace.
     *
     * @param map the parsed flat JSON map
     * @param key the property key
     * @param defaultValue the fallback value applied when the key is missing or blank
     * @param defaulted collects keys that fell back to their default
     * @return the parsed boolean
     */
    private static boolean resolveBoolean(Map<String, String> map, String key, String defaultValue,
            List<String> defaulted) {
        return Boolean.parseBoolean(resolve(map, key, defaultValue, defaulted).trim());
    }

    /**
     * Resolves a long-encoded value, tolerating surrounding whitespace.
     *
     * @param map the parsed flat JSON map
     * @param key the property key
     * @param defaultValue the fallback value applied when the key is missing or blank
     * @param defaulted collects keys that fell back to their default
     * @return the parsed long
     */
    private static long resolveLong(Map<String, String> map, String key, String defaultValue, List<String> defaulted) {
        return Long.parseLong(resolve(map, key, defaultValue, defaulted).trim());
    }

    /**
     * Resolves an int-encoded value, tolerating surrounding whitespace.
     *
     * @param map the parsed flat JSON map
     * @param key the property key
     * @param defaultValue the fallback value applied when the key is missing or blank
     * @param defaulted collects keys that fell back to their default
     * @return the parsed int
     */
    private static int resolveInt(Map<String, String> map, String key, String defaultValue, List<String> defaulted) {
        return Integer.parseInt(resolve(map, key, defaultValue, defaulted).trim());
    }

    /**
     * Converts a persisted profile label back to its enum constant, tolerating case and surrounding whitespace.
     *
     * @param name the stored profile name
     * @return the matching {@link AssaultProfile}, or {@link AssaultProfile#NONE} if the name is invalid
     */
    private static AssaultProfile parseProfile(String name) {
        if (name == null || name.isBlank()) {
            return AssaultProfile.NONE;
        }
        try {
            return AssaultProfile.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid profile '%s' in state file, defaulting to NONE", name);
            return AssaultProfile.NONE;
        }
    }

    /**
     * Converts a persisted response body mode label back to its enum constant, tolerating case and surrounding
     * whitespace.
     *
     * @param name the stored mode name
     * @return the matching {@link ResponseBodyMode}, or {@link ResponseBodyMode#TRUNCATE} if the name is invalid
     */
    private static ResponseBodyMode parseBodyMode(String name) {
        if (name == null || name.isBlank()) {
            return ResponseBodyMode.TRUNCATE;
        }
        try {
            return ResponseBodyMode.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid response body mode '%s' in state file, defaulting to TRUNCATE", name);
            return ResponseBodyMode.TRUNCATE;
        }
    }

    /**
     * Encodes the configured header rules as a JSON object string mapping each header name to a nested
     * {@code {"action": ..., "value": ...}} object string, so the flat state file can carry the whole map under a single
     * key.
     *
     * @param headers the configured rules
     * @return the JSON object string (an empty object when no rules are configured)
     */
    private static String encodeResponseHeaders(Map<String, MutableAssaultConfig.HeaderRule> headers) {
        if (headers.isEmpty()) {
            return "{}";
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        headers.forEach((name, rule) -> {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("action", rule.action().name());
            inner.put("value", rule.value());
            outer.put(name, mapToJson(inner));
        });
        return mapToJson(outer);
    }

    /**
     * Restores the configured header rules from their encoded JSON representation. The state file is user-editable, so
     * every entry is validated before being restored: entries whose outer value is not a nested JSON object, whose
     * action is missing or unknown, or whose value cannot be emitted as an HTTP header are skipped with a warning
     * rather than turned into an unintended injection rule. Missing or blank entries (including an empty object) leave
     * the configuration without any rule.
     *
     * @param encoded the encoded rules, or {@code null} when the state file predates the feature
     * @param config the configuration to populate
     */
    private static void applyResponseHeaders(String encoded, MutableAssaultConfig config) {
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        parseJson(encoded).forEach((name, inner) -> {
            if (name == null || name.isBlank()) {
                LOG.warnf("Skipping response header rule with a blank name in the state file");
                return;
            }
            if (!isJsonObject(inner)) {
                LOG.warnf("Skipping malformed response header rule for '%s' in the state file: expected a nested object",
                        name);
                return;
            }
            Map<String, String> rule = parseJson(inner);
            ResponseHeaderAction action = parseResponseHeaderAction(rule.get("action"));
            if (action == null) {
                LOG.warnf("Skipping response header rule for '%s' in the state file: missing or invalid action", name);
                return;
            }
            String value = rule.get("value") != null ? rule.get("value") : "";
            if (!MutableAssaultConfig.isValidResponseHeaderValue(value)) {
                LOG.warnf("Skipping response header rule for '%s' in the state file: the value cannot be emitted as an "
                        + "HTTP header", name);
                return;
            }
            try {
                config.setResponseHeader(name, action, value);
                LOG.debugf("Goblin: restored response header rule '%s' %s value='%s' from state file", name, action, value);
            } catch (IllegalArgumentException e) {
                LOG.warnf("Skipping invalid response header rule for '%s' in the state file: %s", name, e.getMessage());
            }
        });
    }

    /**
     * Returns whether the persisted value is shaped like a nested JSON object.
     *
     * @param value the persisted value
     * @return {@code true} when the value is non-{@code null} and wrapped in braces
     */
    private static boolean isJsonObject(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    /**
     * Converts a persisted response header action label back to its enum constant, tolerating case and surrounding
     * whitespace. The historical {@code ADD} and {@code OVERRIDE} labels are mapped to {@link ResponseHeaderAction#SET}.
     *
     * @param name the stored action name
     * @return the matching {@link ResponseHeaderAction}, or {@code null} when the name is blank or invalid
     */
    private static ResponseHeaderAction parseResponseHeaderAction(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toUpperCase();
        if ("ADD".equals(normalized) || "OVERRIDE".equals(normalized)) {
            return ResponseHeaderAction.SET;
        }
        try {
            return ResponseHeaderAction.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append("  \"").append(escapeJson(entry.getKey())).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else {
                sb.append(value);
            }
            if (i < map.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
            i++;
        }
        sb.append("}");
        return sb.toString();
    }

    static Map<String, String> parseJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        String content = json.strip();
        if (content.startsWith("{")) {
            content = content.substring(1);
        }
        if (content.endsWith("}")) {
            content = content.substring(0, content.length() - 1);
        }
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '"' || c == '\'') {
                i = parseQuotedEntry(content, i, map);
            } else {
                i++;
            }
        }
        return map;
    }

    private static int parseQuotedEntry(String content, int start, Map<String, String> map) {
        String key = extractQuotedString(content, start);
        if (key == null) {
            return start + 1;
        }
        int keyEnd = skipQuotedString(content, start);
        int colon = indexOfNonWhitespace(content, keyEnd, ':');
        if (colon < 0) {
            return keyEnd;
        }
        int valueStart = indexOfNonWhitespace(content, colon + 1,
                (c) -> c == '"' || c == '\'' || Character.isDigit(c) || c == '-' || c == 't' || c == 'f' || c == 'n');
        if (valueStart < 0 || valueStart >= content.length()) {
            return content.length();
        }
        char vc = content.charAt(valueStart);
        if (vc == '"' || vc == '\'') {
            String value = extractQuotedString(content, valueStart);
            map.put(key, value != null ? value : "");
            return skipQuotedString(content, valueStart);
        } else {
            int end = valueStart;
            while (end < content.length() && content.charAt(end) != ',' && content.charAt(end) != '}'
                    && content.charAt(end) != '\n') {
                end++;
            }
            map.put(key, content.substring(valueStart, end).strip());
            return end;
        }
    }

    private static String extractQuotedString(String content, int start) {
        if (start >= content.length() || (content.charAt(start) != '"' && content.charAt(start) != '\'')) {
            return null;
        }
        char quote = content.charAt(start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == quote) {
                    sb.append(quote);
                    i += 2;
                } else if (next == '\\') {
                    sb.append('\\');
                    i += 2;
                } else if (next == 'n') {
                    sb.append('\n');
                    i += 2;
                } else if (next == 't') {
                    sb.append('\t');
                    i += 2;
                } else {
                    sb.append(next);
                    i += 2;
                }
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int skipQuotedString(String content, int start) {
        if (start >= content.length() || (content.charAt(start) != '"' && content.charAt(start) != '\'')) {
            return start;
        }
        char quote = content.charAt(start);
        int i = start + 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int indexOfNonWhitespace(String content, int from, char target) {
        for (int i = from; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == target) {
                return i;
            } else if (!Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static int indexOfNonWhitespace(String content, int from, java.util.function.IntPredicate predicate) {
        for (int i = from; i < content.length(); i++) {
            char c = content.charAt(i);
            if (predicate.test(c)) {
                return i;
            } else if (!Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
