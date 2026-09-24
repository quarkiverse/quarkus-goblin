package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class TargetRulesTest {

    @Test
    void everyPackageIsTargetedWithoutRules() {
        TargetRules rules = new TargetRules(List.of(), List.of(), List.of());
        assertTrue(rules.isPackageTargeted("com.acme.orders"));
        assertFalse(rules.isExcludedBy(List.of("jakarta.ws.rs.GET")));
    }

    @Test
    void includePrefixesRestrictTheTargetedPackages() {
        TargetRules rules = new TargetRules(List.of("com.acme"), List.of(), List.of());
        assertTrue(rules.isPackageTargeted("com.acme.orders"));
        assertFalse(rules.isPackageTargeted("org.other"));
    }

    @Test
    void anExclusionAlwaysWinsOverAnInclusion() {
        TargetRules rules = new TargetRules(List.of("com.acme"), List.of("com.acme.internal"), List.of());
        assertTrue(rules.isPackageTargeted("com.acme.orders"));
        assertFalse(rules.isPackageTargeted("com.acme.internal.jobs"));
    }

    @Test
    void excludingAnnotationsMatchByFullyQualifiedName() {
        TargetRules rules = new TargetRules(List.of(), List.of(),
                List.of("org.eclipse.microprofile.faulttolerance.Timeout"));
        assertTrue(rules.isExcludedBy(List.of("jakarta.ws.rs.GET", "org.eclipse.microprofile.faulttolerance.Timeout")));
        assertFalse(rules.isExcludedBy(List.of("Timeout")));
    }

    @Test
    void blankEntriesAreIgnored() {
        TargetRules rules = new TargetRules(List.of(" ", ""), List.of(" com.acme.internal "), List.of(" "));
        assertTrue(rules.isPackageTargeted("org.other"), "blank include prefixes must not restrict anything");
        assertFalse(rules.isPackageTargeted("com.acme.internal"), "entries are trimmed");
    }
}
