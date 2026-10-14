package io.quarkiverse.goblin.route;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.TargetRules;
import io.quarkiverse.goblin.assault.AbortResponses;

class GoblinRouteHookTest {

    @Test
    void parsesTheGeneratedHandlerName() {
        assertArrayEquals(new String[] { "com.acme.GreetingRoutes", "hello" },
                GoblinRouteHook.parseHandlerName(
                        "com.acme.GreetingRoutes_RouteHandler_hello_0123456789abcdef0123456789abcdef01234567"));
    }

    @Test
    void keepsUnderscoresOfTheMethodName() {
        assertArrayEquals(new String[] { "com.acme.Routes", "say_hello" },
                GoblinRouteHook.parseHandlerName("com.acme.Routes_RouteHandler_say_hello_abc123"));
    }

    @Test
    void ignoresClassesThatAreNotGeneratedRouteHandlers() {
        assertNull(GoblinRouteHook.parseHandlerName("com.acme.MyHandler"));
        assertNull(GoblinRouteHook.parseHandlerName("com.acme.Routes_RouteHandler_nohash"));
    }

    @Test
    void routeAbortFollowsTheDeclaredProduces() throws Exception {
        assertTrue(AbortResponses.routeWantsJson(new String[] { "application/json" }, String.class));
        assertFalse(AbortResponses.routeWantsJson(new String[] { "text/plain" }, Map.class));
    }

    @Test
    void routeAbortFollowsTheReturnTypeWithoutProduces() throws Exception {
        assertTrue(AbortResponses.routeWantsJson(new String[0],
                Fixtures.class.getDeclaredMethod("pojo").getGenericReturnType()));
        assertFalse(AbortResponses.routeWantsJson(new String[0], String.class));
        assertFalse(AbortResponses.routeWantsJson(new String[0], void.class));
        assertFalse(AbortResponses.routeWantsJson(new String[0], io.vertx.core.buffer.Buffer.class));
    }

    @Test
    void appliesTheTargetingRulesLikeTheRestFilter() throws Exception {
        String chaosOff = ChaosOff.class.getName();
        TargetRules excludeAnnotation = new TargetRules(List.of(), List.of(), List.of(chaosOff));
        Method plain = Fixtures.class.getDeclaredMethod("pojo");

        GoblinRouteHook.RouteTarget target = GoblinRouteHook.target(plain, NO_RULES);
        assertEquals("Fixtures.pojo", target.description());
        assertTrue(target.eligible());
        assertFalse(target.excluded());

        assertFalse(GoblinRouteHook.target(Fixtures.class.getDeclaredMethod("spared"), excludeAnnotation).eligible(),
                "an excluding annotation on the method spares it");
        assertFalse(GoblinRouteHook.target(SparedClass.class.getDeclaredMethod("any"), excludeAnnotation).eligible(),
                "an excluding annotation on the class spares its methods");

        TargetRules excludePackage = new TargetRules(List.of(), List.of(getClass().getPackageName()), List.of());
        GoblinRouteHook.RouteTarget excluded = GoblinRouteHook.target(plain, excludePackage);
        assertFalse(excluded.eligible());
        assertTrue(excluded.excluded(), "an excluded package shields the whole request");

        TargetRules includeOther = new TargetRules(List.of("com.other"), List.of(), List.of());
        GoblinRouteHook.RouteTarget notIncluded = GoblinRouteHook.target(plain, includeOther);
        assertFalse(notIncluded.eligible());
        assertFalse(notIncluded.excluded(), "a route outside include-packages is not assaulted, but not shielded");
    }

    private static final TargetRules NO_RULES = new TargetRules(List.of(), List.of(), List.of());

    @Retention(RetentionPolicy.RUNTIME)
    @interface ChaosOff {
    }

    @SuppressWarnings("unused")
    private static final class Fixtures {
        List<String> pojo() {
            return List.of();
        }

        @ChaosOff
        String spared() {
            return "spared";
        }
    }

    @ChaosOff
    @SuppressWarnings("unused")
    private static final class SparedClass {
        String any() {
            return "any";
        }
    }
}
