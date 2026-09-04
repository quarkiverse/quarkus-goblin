package io.quarkiverse.goblin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Handles persistence of {@link MutableAssaultConfig} to disk.
 * Serializes to a flat JSON file so Dev UI changes survive restarts.
 */
public final class GoblinStatePersistence {

    private static final Logger LOG = Logger.getLogger(GoblinStatePersistence.class);
    private static final String STATE_FILE = ".goblin-state.json";

    private GoblinStatePersistence() {
    }

    public static void save(MutableAssaultConfig config) {
        try {
            Files.writeString(Path.of(STATE_FILE), toJson(config));
        } catch (Exception e) {
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
        } catch (Exception e) {
            LOG.warnf("Failed to load .goblin-state.json, falling back to application.properties: %s",
                    e.getMessage());
            return null;
        }
    }

    private static String toJson(MutableAssaultConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
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

    static MutableAssaultConfig fromJson(String json) {
        Map<String, String> map = parseJson(json);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(Boolean.parseBoolean(map.getOrDefault("latencyEnabled", "true")));
        config.setExceptionEnabled(Boolean.parseBoolean(map.getOrDefault("exceptionEnabled", "false")));
        config.setHttpStatusEnabled(Boolean.parseBoolean(map.getOrDefault("httpStatusEnabled", "false")));
        config.setDependencyDegradationEnabled(
                Boolean.parseBoolean(map.getOrDefault("dependencyDegradationEnabled", "false")));
        config.setLatencyMinMs(Long.parseLong(map.getOrDefault("latencyMinMs", "100")));
        config.setLatencyMaxMs(Long.parseLong(map.getOrDefault("latencyMaxMs", "5000")));
        config.setExceptionType(map.getOrDefault("exceptionType", "java.lang.RuntimeException"));
        config.setExceptionMessage(map.getOrDefault("exceptionMessage", "Goblin chaos: simulated exception"));
        config.setHttpStatusCode(Integer.parseInt(map.getOrDefault("httpStatusCode", "503")));
        config.setHttpStatusMessage(map.getOrDefault("httpStatusMessage", "Service Unavailable (Goblin chaos)"));
        config.setTargetLevel(Integer.parseInt(map.getOrDefault("targetLevel", "100")));
        return config;
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

    private static Map<String, String> parseJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        String content = json.strip();
        if (content.startsWith("{")) {
            content = content.substring(1);
        }
        if (content.endsWith("}")) {
            content = content.substring(0, content.length() - 1);
        }
        for (String line : content.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            line = line.replace(",", "");
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().replace("\"", "");
            String val = line.substring(colon + 1).strip();
            if (val.startsWith("\"") && val.endsWith("\"")) {
                val = unescapeJson(val.substring(1, val.length() - 1));
            }
            map.put(key, val);
        }
        return map;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
