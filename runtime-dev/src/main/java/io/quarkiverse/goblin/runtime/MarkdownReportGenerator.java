package io.quarkiverse.goblin.runtime;

import java.time.Instant;
import java.util.List;

final class MarkdownReportGenerator {

    private static final String CONFIG_TEMPLATE = """
            - Latency (enabled: %s): %d - %d ms
            - Exception (enabled: %s): %s - "%s"
            - HTTP Status (enabled: %s): %d - "%s"
            - Dependency Degradation (enabled: %s): HTTP 503 with fixed body
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

    static String build(boolean active, MutableAssaultConfig cfg, List<AssaultEngine.AssaultRecord> history) {
        String configSection = formatConfig(cfg);
        String historySection = formatHistory(history);

        return String.format(REPORT_TEMPLATE,
                Instant.now(),
                active ? "ACTIVE" : "INACTIVE",
                cfg != null ? cfg.getTargetLevel() + "%" : "n/a",
                configSection,
                historySection);
    }

    private static String formatConfig(MutableAssaultConfig cfg) {
        if (cfg == null) {
            return "- No configuration available.\n";
        }
        return String.format(CONFIG_TEMPLATE,
                cfg.isLatencyEnabled(), cfg.getLatencyMinMs(), cfg.getLatencyMaxMs(),
                cfg.isExceptionEnabled(), cfg.getExceptionType(), cfg.getExceptionMessage(),
                cfg.isHttpStatusEnabled(), cfg.getHttpStatusCode(), cfg.getHttpStatusMessage(),
                cfg.isDependencyDegradationEnabled());
    }

    private static String formatHistory(List<AssaultEngine.AssaultRecord> history) {
        if (history.isEmpty()) {
            return "No assaults have been recorded yet.";
        }
        StringBuilder sb = new StringBuilder("| # | Time | Method | Type | Details |\n");
        sb.append("|---|---|---|---|---|\n");
        int i = 1;
        for (AssaultEngine.AssaultRecord record : history) {
            sb.append("| ").append(i).append(" | ")
                    .append(Instant.ofEpochMilli(record.timestamp())).append(" | ")
                    .append(record.method()).append(" | ")
                    .append(record.type()).append(" | ")
                    .append(details(record)).append(" |\n");
            i++;
        }
        return sb.toString();
    }

    private static String details(AssaultEngine.AssaultRecord record) {
        if ("latency".equals(record.type()) && record.latencyMs() > 0) {
            return record.latencyMs() + " ms";
        }
        return "-";
    }
}
