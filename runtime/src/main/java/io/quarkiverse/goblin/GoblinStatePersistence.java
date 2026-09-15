package io.quarkiverse.goblin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Handles persistence of {@link MutableAssaultConfig} to disk.
 * Serializes to a flat JSON file so Dev UI changes survive restarts.
 * <p>
 * Note: {@link #save(MutableAssaultConfig)} and {@link #load()} use a fixed {@code .goblin-state.json} file in the
 * process working directory. Callers (and tests) that write state must clean up afterwards.
 */
public final class GoblinStatePersistence {

    private static final Logger LOG = Logger.getLogger(GoblinStatePersistence.class);
    private static final String STATE_FILE = ".goblin-state.json";

    private GoblinStatePersistence() {
    }

    public static void save(MutableAssaultConfig config) {
        try {
            Files.writeString(Path.of(STATE_FILE), toJson(config));
        } catch (IOException e) {
            LOG.warnf("Failed to persist .goblin-state.json: %s", e.getMessage());
        }
    }

    public static MutableAssaultConfig load() {
        Path path = Path.of(STATE_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return fromJson(Files.readString(path));
        } catch (IOException e) {
            LOG.warnf("Failed to load .goblin-state.json, falling back to application.properties: %s",
                    e.getMessage());
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
        map.put("latencyMinMs", config.getLatencyMinMs());
        map.put("latencyMaxMs", config.getLatencyMaxMs());
        map.put("exceptionType", config.getExceptionType());
        map.put("exceptionMessage", config.getExceptionMessage());
        map.put("httpStatusCode", config.getHttpStatusCode());
        map.put("httpStatusMessage", config.getHttpStatusMessage());
        map.put("targetLevel", config.getTargetLevel());
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
        config.setLatencyMinMs(resolveLong(map, "latencyMinMs", "100", defaulted));
        config.setLatencyMaxMs(resolveLong(map, "latencyMaxMs", "5000", defaulted));
        config.setExceptionType(resolve(map, "exceptionType", "java.lang.RuntimeException", defaulted));
        config.setExceptionMessage(resolve(map, "exceptionMessage", "Goblin chaos: simulated exception", defaulted));
        config.setHttpStatusCode(resolveInt(map, "httpStatusCode", "503", defaulted));
        config.setHttpStatusMessage(resolve(map, "httpStatusMessage", "Service Unavailable (Goblin chaos)", defaulted));
        config.setTargetLevel(resolveInt(map, "targetLevel", "100", defaulted));
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
