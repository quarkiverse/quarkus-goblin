package io.quarkiverse.goblin.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.ChaosLayer;

class MarkdownReportGeneratorTest {

    @Test
    void reportDescribesTheArmedLayersAndTheClientAssaults() {
        MutableAssaultConfigImpl cfg = new MutableAssaultConfigImpl();
        cfg.setLayers(List.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE));
        cfg.setClientExceptionEnabled(true);

        String report = MarkdownReportGenerator.build(true, cfg, List.of());

        assertTrue(report.contains("- Chaos layers: DATABASE, SERVICE"), report);
        assertTrue(report.contains("- Client-side assaults: latency (enabled: false), exception (enabled: true)"), report);
    }

    @Test
    void historyRowsCarryTheSource() {
        AssaultEngine.AssaultRecord record = new AssaultEngine.AssaultRecord("Database <default> connection",
                "exception", 0, 0, "exception enabled", AssaultSource.DATABASE, AssaultType.EXCEPTION);

        String report = MarkdownReportGenerator.build(true, new MutableAssaultConfigImpl(), List.of(record));

        assertTrue(report.contains("| # | Time | Method | Source | Type |"), report);
        assertTrue(report.contains("| Database <default> connection | database | exception |"), report);
    }
}
