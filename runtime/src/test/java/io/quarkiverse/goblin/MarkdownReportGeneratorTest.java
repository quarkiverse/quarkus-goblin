package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownReportGeneratorTest {

    @Test
    void reportDescribesTheArmedLayersAndTheClientAssaults() {
        MutableAssaultConfig cfg = new MutableAssaultConfig();
        cfg.setLayers(List.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE));
        cfg.setClientExceptionEnabled(true);

        String report = MarkdownReportGenerator.build(true, cfg, List.of());

        assertTrue(report.contains("- Chaos layers: DATABASE, SERVICE"), report);
        assertTrue(report.contains("- Client-side assaults: latency (enabled: false), exception (enabled: true)"), report);
    }

    @Test
    void historyRowsCarryTheSource() {
        AssaultEngine.AssaultRecord record = new AssaultEngine.AssaultRecord("Database <default> connection",
                "exception", 0, 0, "exception enabled", AssaultSource.DATABASE);

        String report = MarkdownReportGenerator.build(true, new MutableAssaultConfig(), List.of(record));

        assertTrue(report.contains("| # | Time | Method | Source | Type |"), report);
        assertTrue(report.contains("| Database <default> connection | database | exception |"), report);
    }
}
