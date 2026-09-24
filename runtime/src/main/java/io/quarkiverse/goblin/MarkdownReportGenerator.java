package io.quarkiverse.goblin;

import java.time.Instant;
import java.util.List;

public final class MarkdownReportGenerator {

    private static final String CONFIG_TEMPLATE = """
            - Profile: %s
            - Chaos layers: %s
            - Latency (enabled: %s): %d - %d ms
            - Exception (enabled: %s): %s - "%s"
            - HTTP Status (enabled: %s): %d - "%s"
            - Dependency Degradation (enabled: %s): HTTP 503 with fixed body
            - Response Body (enabled: %s): %s %d%%
            - Response Headers (enabled: %s): %s
            - Client-side assaults: latency (enabled: %s), exception (enabled: %s)
            """;

    private static final String REPORT_TEMPLATE = """
            # Goblin Chaos Report

            _Generated %s_

            | Field | Value |
            |---|---|
            | Status | %s |
            | Target level | %s |

            ## Current Configuration

            %s
            ## Assault History

            %s
            """;

    private MarkdownReportGenerator() {
    }

    public static String build(boolean active, MutableAssaultConfig cfg, List<AssaultEngine.AssaultRecord> history) {
        String configSection = formatConfig(cfg);
        String historySection = formatHistory(history);

        return String.format(REPORT_TEMPLATE,
                Instant.now(),
                active ? "ACTIVE" : "INACTIVE",
                cfg != null ? cfg.getTargetLevel() + "%" : "n/a",
                configSection,
                historySection);
    }

    /**
     * Renders the current configuration section of the markdown report, including the active profile.
     *
     * @param cfg the current mutable assault configuration
     * @return a bullet list describing the configuration, or a placeholder when no configuration is available
     */
    private static String formatConfig(MutableAssaultConfig cfg) {
        if (cfg == null) {
            return "- No configuration available.\n";
        }
        return String.format(CONFIG_TEMPLATE,
                cfg.getProfile(),
                cfg.getLayers().stream().map(ChaosLayer::name).collect(java.util.stream.Collectors.joining(", ")),
                cfg.isLatencyEnabled(), cfg.getLatencyMinMs(), cfg.getLatencyMaxMs(),
                cfg.isExceptionEnabled(), cfg.getExceptionType(), cfg.getExceptionMessage(),
                cfg.isHttpStatusEnabled(), cfg.getHttpStatusCode(), cfg.getHttpStatusMessage(),
                cfg.isDependencyDegradationEnabled(),
                cfg.isResponseBodyEnabled(), cfg.getResponseBodyMode(), cfg.getResponseBodyPercentage(),
                cfg.isResponseHeaderEnabled(), cfg.describeResponseHeaders(),
                cfg.isClientLatencyEnabled(), cfg.isClientExceptionEnabled());
    }

    private static String formatHistory(List<AssaultEngine.AssaultRecord> history) {
        if (history.isEmpty()) {
            return "No assaults have been recorded yet.";
        }
        StringBuilder sb = new StringBuilder(
                "| # | Time | Method | Source | Type | Injected Value | Active Config at Time of Assault |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (AssaultEngine.AssaultRecord record : history) {
            sb.append("| ").append(i).append(" | ")
                    .append(Instant.ofEpochMilli(record.timestamp())).append(" | ")
                    .append(record.method()).append(" | ")
                    .append(record.sourceTag()).append(" | ")
                    .append(record.type()).append(" | ")
                    .append(injectedValue(record)).append(" | ")
                    .append(record.configSnapshot()).append(" |\n");
            i++;
        }
        return sb.toString();
    }

    private static String injectedValue(AssaultEngine.AssaultRecord record) {
        if ("latency".equals(record.type()) && record.latencyMs() > 0) {
            return record.latencyMs() + " ms";
        }
        return "-";
    }
}
