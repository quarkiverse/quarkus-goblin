package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.dev.GoblinJsonRPCService;

/**
 * The playbook tells an agent what to do, the Dev MCP tools are how it does it: they must agree on names. A renamed tool
 * or field fails here instead of leaving the agent with an instruction it cannot follow.
 */
class AgentPlaybookTest {

    /**
     * The non-tool identifiers the playbook names: fields of the tool results, the inventory resource and its fields,
     * configuration keys and annotation parameters. Each is an explicit entry, so a new one is a deliberate edit.
     */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "availableLayers", "level", "latencyMs", "source", "clientLatencyEnabled", "clientExceptionEnabled",
            "resilienceInventory", "metricMethodTag", "reachableBy", "requestVolumeThreshold", "circuitBreakerOpen");

    private static final Pattern BACKTICKED_IDENTIFIER = Pattern.compile("`([a-z][A-Za-z]+)(\\([^`]*\\))?`");

    @Test
    void everyNamedToolExists() {
        Set<String> tools = Arrays.stream(GoblinJsonRPCService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        List<String> unknown = new ArrayList<>();
        Set<String> named = new TreeSet<>();
        Matcher matcher = BACKTICKED_IDENTIFIER.matcher(GoblinDevUIProcessor.readPlaybook());
        while (matcher.find()) {
            String identifier = matcher.group(1);
            named.add(identifier);
            if (!tools.contains(identifier) && !KNOWN_FIELDS.contains(identifier)) {
                unknown.add(identifier);
            }
        }

        assertEquals(List.of(), unknown, "the playbook names a tool or field that does not exist");
        assertTrue(named.containsAll(Set.of("startAutoOff", "getConfig", "disableAll", "getHistory")),
                "the playbook lost one of its core safety instructions: " + named);
    }

    @Test
    void resourceNamesFitTheDevMcpUri() {
        // quarkus://resource/build-time/<namespace>_<name>: Quarkus splits on the underscore
        assertFalse(GoblinDevUIProcessor.PLAYBOOK_RESOURCE.contains("_"));
        assertFalse(GoblinDevUIProcessor.INVENTORY_RESOURCE.contains("_"));
        assertTrue(GoblinDevUIProcessor.readPlaybook().contains("`" + GoblinDevUIProcessor.INVENTORY_RESOURCE + "`"),
                "the playbook must point the agent to the inventory resource by its exact name");
    }
}
