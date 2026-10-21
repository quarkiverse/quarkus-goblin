package io.quarkiverse.goblin.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultConfigChange;
import io.quarkiverse.goblin.AssaultEngine.AssaultRecord;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.DeactivationReason;

/**
 * Writes the structured events of a chaos experiment -- each assault, chaos switched on or off, a configuration change
 * -- to the {@value #CATEGORY} log category, so whoever runs the experiment, human or agent, can reason about it from
 * the log alone.
 * <p>
 * Each event is a single line of {@code key=value} pairs after a {@code goblin} marker, values quoted when they hold a
 * space, a quote or an {@code =}, and control characters escaped so an event never spans two lines:
 *
 * <pre>
 * goblin event=assault timestamp=2026-10-21T10:02:03.120Z source=server method=GreetingResource.hello type=latency assault-type=latency value=842ms config="latency enabled (500 - 1000 ms)"
 * </pre>
 *
 * The same fields are put in the MDC under a {@code goblin.} prefix ({@code goblin.event}, {@code goblin.method}...)
 * while the line is logged, so a JSON log formatter emits them as fields. Assaults are logged at {@code INFO}, the
 * other events at {@code WARN}: raising the category level to {@code WARN} silences the per-assault events and keeps
 * the experiment lifecycle.
 */
public final class GoblinEventLog {

    /**
     * The log category of the events.
     */
    public static final String CATEGORY = "io.quarkiverse.goblin.events";

    /**
     * The prefix of the MDC keys holding the event fields.
     */
    public static final String MDC_PREFIX = "goblin.";

    private static final Logger LOG = Logger.getLogger(CATEGORY);

    private GoblinEventLog() {
    }

    /**
     * Logs an assault, at {@code INFO}.
     *
     * @param record the recorded assault
     */
    public static void assault(AssaultRecord record) {
        if (!LOG.isEnabled(Logger.Level.INFO)) {
            return;
        }
        Map<String, String> fields = event("assault", record.timestamp());
        fields.put("source", record.sourceTag());
        fields.put("method", record.method());
        fields.put("type", record.type());
        fields.put("assault-type", record.assaultType() != null ? tag(record.assaultType().name()) : "");
        fields.put("value", record.injectedValue());
        fields.put("config", record.configSnapshot());
        log(Logger.Level.INFO, fields);
    }

    /**
     * Logs chaos switched on, at {@code WARN}.
     *
     * @param origin what switched it on: {@code startup} or {@code manual}
     * @param config the configuration chaos runs with
     */
    public static void activated(String origin, AssaultConfig config) {
        if (!LOG.isEnabled(Logger.Level.WARN)) {
            return;
        }
        Map<String, String> fields = event("activated", System.currentTimeMillis());
        fields.put("origin", origin);
        putConfig(fields, config);
        log(Logger.Level.WARN, fields);
    }

    /**
     * Logs chaos switched off, at {@code WARN}.
     *
     * @param reason why chaos was switched off
     */
    public static void deactivated(DeactivationReason reason) {
        if (!LOG.isEnabled(Logger.Level.WARN)) {
            return;
        }
        Map<String, String> fields = event("deactivated", System.currentTimeMillis());
        fields.put("reason", reason.tag());
        log(Logger.Level.WARN, fields);
    }

    /**
     * Logs an auto-off scheduled, at {@code WARN}.
     *
     * @param deadline the epoch time (ms) chaos switches itself off
     */
    public static void autoOffScheduled(long deadline) {
        if (!LOG.isEnabled(Logger.Level.WARN)) {
            return;
        }
        Map<String, String> fields = event("auto-off-scheduled", System.currentTimeMillis());
        fields.put("deadline", Instant.ofEpochMilli(deadline).toString());
        log(Logger.Level.WARN, fields);
    }

    /**
     * Logs a change of the assault configuration, at {@code WARN}.
     *
     * @param change the configuration before and after the change
     */
    public static void configChange(AssaultConfigChange change) {
        if (!LOG.isEnabled(Logger.Level.WARN)) {
            return;
        }
        Map<String, String> fields = event("config-change", change.timestamp());
        putConfig(fields, change.current());
        fields.put("previous", change.previousDescription());
        log(Logger.Level.WARN, fields);
    }

    private static Map<String, String> event(String name, long timestamp) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", name);
        fields.put("timestamp", Instant.ofEpochMilli(timestamp).toString());
        return fields;
    }

    private static void putConfig(Map<String, String> fields, AssaultConfig config) {
        fields.put("profile", config.getProfile().name());
        fields.put("level", Integer.toString(config.getTargetLevel()));
        fields.put("layers", config.getLayers().stream().map(ChaosLayer::name).collect(Collectors.joining(",")));
        fields.put("config", config.describeAssaults());
    }

    private static String tag(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void log(Logger.Level level, Map<String, String> fields) {
        Map<String, Object> previous = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String key = MDC_PREFIX + field.getKey();
            previous.put(key, MDC.put(key, field.getValue() != null ? field.getValue() : ""));
        }
        try {
            LOG.log(level, format(fields));
        } finally {
            for (Map.Entry<String, Object> entry : previous.entrySet()) {
                if (entry.getValue() != null) {
                    MDC.put(entry.getKey(), entry.getValue());
                } else {
                    MDC.remove(entry.getKey());
                }
            }
        }
    }

    /**
     * Formats the fields as a single {@code key=value} line after a {@code goblin} marker. Package-private for tests.
     *
     * @param fields the event fields, in order
     * @return the log line
     */
    static String format(Map<String, String> fields) {
        StringBuilder line = new StringBuilder("goblin");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            line.append(' ').append(field.getKey()).append('=').append(quote(field.getValue()));
        }
        return line.toString();
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        boolean quoted = false;
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"', '\\' -> {
                    escaped.append('\\').append(c);
                    quoted = true;
                }
                case '\n' -> {
                    escaped.append("\\n");
                    quoted = true;
                }
                case '\r' -> {
                    escaped.append("\\r");
                    quoted = true;
                }
                case '\t' -> {
                    escaped.append("\\t");
                    quoted = true;
                }
                case ' ', '=' -> {
                    escaped.append(c);
                    quoted = true;
                }
                default -> {
                    if (Character.isISOControl(c)) {
                        escaped.append(String.format("\\u%04x", (int) c));
                        quoted = true;
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return quoted ? "\"" + escaped + "\"" : escaped.toString();
    }
}
