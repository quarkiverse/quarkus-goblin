package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.TargetRules;
import io.smallrye.faulttolerance.api.RateLimit;

class ResilienceInventoryTest {

    private static final TargetRules NO_RULES = new TargetRules(List.of(), List.of(), List.of());

    static class GuardedService {

        @Timeout(value = 400, unit = ChronoUnit.MILLIS)
        public String timed() {
            return "timed";
        }

        @Retry(maxRetries = 2, abortOn = IllegalArgumentException.class)
        @Fallback(fallbackMethod = "fallback")
        public String flaky() {
            return "flaky";
        }

        public String plain() {
            return "plain";
        }

        String fallback(Throwable cause) {
            return "fallback";
        }
    }

    @CircuitBreaker(requestVolumeThreshold = 4)
    static class ClassLevelGuard {

        public String inherited() {
            return "inherited";
        }

        @CircuitBreaker(failureRatio = 1.0)
        public String overridden() {
            return "overridden";
        }

        private String hidden() {
            return "hidden";
        }

        public static String helper() {
            return "helper";
        }
    }

    @Path("/guarded")
    static class GuardedResource {

        @GET
        @Timeout(200)
        public String get() {
            return "get";
        }
    }

    @RegisterRestClient
    interface GuardedClient {

        @Retry(maxRetries = 1)
        String call();
    }

    static class RateLimited {

        @RateLimit(value = 5)
        public String limited() {
            return "limited";
        }
    }

    private static List<Map<String, Object>> inventory(TargetRules rules) throws IOException {
        Index index = Index.of(GuardedService.class, ClassLevelGuard.class, GuardedResource.class, GuardedClient.class,
                RateLimited.class);
        return ResilienceInventory.of(index, index, rules);
    }

    private static Map<String, Object> entry(List<Map<String, Object>> inventory, Class<?> clazz, String method) {
        return inventory.stream()
                .filter(e -> e.get("class").equals(clazz.getName()) && e.get("method").equals(method))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> annotation(Map<String, Object> entry, String name) {
        return (Map<String, Object>) ((Map<String, Object>) entry.get("annotations")).get(name);
    }

    @Test
    void listsOnlyGuardedMethods() throws IOException {
        List<Map<String, Object>> inventory = inventory(NO_RULES);

        List<String> listed = inventory.stream().map(e -> e.get("class") + "#" + e.get("method")).toList();
        assertEquals(List.of(
                ClassLevelGuard.class.getName() + "#inherited",
                ClassLevelGuard.class.getName() + "#overridden",
                GuardedClient.class.getName() + "#call",
                GuardedResource.class.getName() + "#get",
                GuardedService.class.getName() + "#flaky",
                GuardedService.class.getName() + "#timed",
                RateLimited.class.getName() + "#limited"), listed,
                "unguarded, private, static and fallback methods are not listed; entries are sorted");
    }

    @Test
    void reportsDeclaredValuesMergedOverTheSpecificationDefaults() throws IOException {
        List<Map<String, Object>> inventory = inventory(NO_RULES);

        Map<String, Object> timeout = annotation(entry(inventory, GuardedService.class, "timed"), "Timeout");
        assertEquals(400L, timeout.get("value"));
        assertEquals("MILLIS", timeout.get("unit"));

        Map<String, Object> flaky = entry(inventory, GuardedService.class, "flaky");
        Map<String, Object> retry = annotation(flaky, "Retry");
        assertEquals(2, retry.get("maxRetries"));
        assertEquals(200L, retry.get("jitter"), "an undeclared parameter carries its specification default");
        assertEquals("MILLIS", retry.get("jitterDelayUnit"), "every duration comes with its unit");
        assertEquals(List.of("java.lang.IllegalArgumentException"), retry.get("abortOn"));
        assertEquals("fallback", annotation(flaky, "Fallback").get("fallbackMethod"));

        assertEquals(5, annotation(entry(inventory, RateLimited.class, "limited"), "RateLimit").get("value"));
    }

    @Test
    void appliesClassLevelAnnotationsAndLetsTheMethodLevelOneWin() throws IOException {
        List<Map<String, Object>> inventory = inventory(NO_RULES);

        Map<String, Object> inherited = annotation(entry(inventory, ClassLevelGuard.class, "inherited"), "CircuitBreaker");
        assertEquals(4, inherited.get("requestVolumeThreshold"));
        assertEquals(0.5d, inherited.get("failureRatio"));

        Map<String, Object> overridden = annotation(entry(inventory, ClassLevelGuard.class, "overridden"),
                "CircuitBreaker");
        assertEquals(1.0d, overridden.get("failureRatio"));
        assertEquals(20, overridden.get("requestVolumeThreshold"),
                "a method-level annotation replaces the class-level one, it is not merged with it");
    }

    @Test
    void tellsWhichLayersReachTheGuard() throws IOException {
        List<Map<String, Object>> inventory = inventory(NO_RULES);

        Map<String, Object> service = entry(inventory, GuardedService.class, "timed");
        assertEquals(List.of("SERVICE"), service.get("reachableBy"));
        assertNull(service.get("note"));
        assertEquals(GuardedService.class.getName() + ".timed", service.get("metricMethodTag"));

        assertEquals(List.of("HTTP_OUT"), entry(inventory, GuardedClient.class, "call").get("reachableBy"));

        Map<String, Object> resource = entry(inventory, GuardedResource.class, "get");
        assertEquals(List.of(), resource.get("reachableBy"));
        assertTrue(((String) resource.get("note")).startsWith("JAX-RS resource"));
    }

    @Test
    void anApplicationWithoutGuardsGetsOneExplicitEntry() throws IOException {
        Index index = Index.of(Unguarded.class);

        List<Map<String, Object>> inventory = ResilienceInventory.of(index, index, NO_RULES);

        assertEquals(1, inventory.size());
        assertEquals(ResilienceInventory.NONE, inventory.get(0).get("class"));
        assertEquals(ResilienceInventory.NO_GUARD, inventory.get(0).get("note"));
    }

    static class Unguarded {

        public String plain() {
            return "plain";
        }
    }

    @Test
    void followsTheTargetingRulesOfTheServiceLayer() throws IOException {
        TargetRules excludeAll = new TargetRules(List.of(), List.of("io.quarkiverse.goblin.deployment"), List.of());

        Map<String, Object> excluded = entry(inventory(excludeAll), GuardedService.class, "timed");
        assertEquals(List.of(), excluded.get("reachableBy"));
        assertFalse(((String) excluded.get("note")).startsWith("JAX-RS"));
    }
}
