package io.quarkiverse.goblin.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import io.quarkiverse.goblin.TargetRules;

/**
 * Builds the resilience inventory of an application: where it believes it is protected, read from the MicroProfile
 * Fault Tolerance annotations of its own classes. An AI agent reads it as a Dev MCP resource to derive its experiments
 * without reading the code.
 * <p>
 * Each entry is one guarded method: its class, its name, the Fault Tolerance metrics {@code method} tag, its annotations
 * with their main parameters (the declared values merged over the specification defaults, so the agent reads the
 * effective value rather than guessing a default), and the Goblin layers whose faults reach the method <em>inside</em>
 * its Fault Tolerance interceptor. A class-level annotation applies to every business method of the class, and a
 * method-level annotation of the same type wins over it, as in the specification.
 * <p>
 * The values are the ones declared in the code: MicroProfile Config overrides ({@code <class>/<method>/Timeout/value}
 * and friends) and annotations inherited from a superclass are not reflected.
 */
final class ResilienceInventory {

    private static final String MP_FT = "org.eclipse.microprofile.faulttolerance.";

    /**
     * The guarding annotations, in the order they are reported, each with its main parameters and their specification
     * default. Durations are reported with their unit, never converted.
     */
    private static final Map<DotName, Map<String, Object>> ANNOTATIONS = new LinkedHashMap<>();

    static {
        ANNOTATIONS.put(DotName.createSimple(MP_FT + "Timeout"), defaults(
                "value", 1000L, "unit", "MILLIS"));
        ANNOTATIONS.put(DotName.createSimple(MP_FT + "Retry"), defaults(
                "maxRetries", 3, "delay", 0L, "delayUnit", "MILLIS", "maxDuration", 180000L, "durationUnit", "MILLIS",
                "jitter", 200L, "jitterDelayUnit", "MILLIS", "retryOn", List.of("java.lang.Exception"), "abortOn", List.of()));
        ANNOTATIONS.put(DotName.createSimple(MP_FT + "CircuitBreaker"), defaults(
                "requestVolumeThreshold", 20, "failureRatio", 0.5d, "delay", 5000L, "delayUnit", "MILLIS",
                "successThreshold", 1, "failOn", List.of("java.lang.Throwable"), "skipOn", List.of()));
        ANNOTATIONS.put(DotName.createSimple(MP_FT + "Fallback"), defaults(
                "fallbackMethod", "", "value", MP_FT + "Fallback$DEFAULT", "applyOn", List.of("java.lang.Throwable"),
                "skipOn", List.of()));
        ANNOTATIONS.put(DotName.createSimple(MP_FT + "Bulkhead"), defaults(
                "value", 10, "waitingTaskQueue", 10));
        ANNOTATIONS.put(DotName.createSimple("io.smallrye.faulttolerance.api.RateLimit"), defaults(
                "value", 100, "window", 1L, "windowUnit", "SECONDS", "minSpacing", 0L, "minSpacingUnit", "SECONDS"));
    }

    private static final DotName REGISTER_REST_CLIENT = DotName.createSimple(
            "org.eclipse.microprofile.rest.client.inject.RegisterRestClient");

    /** The class and method of the single entry listed when the application declares no guard. */
    static final String NONE = "-";
    static final String NO_GUARD = "No method of this application is guarded by a MicroProfile Fault Tolerance "
            + "annotation: there is no declared protection to verify.";

    private ResilienceInventory() {
    }

    /**
     * Lists the guarded methods of the application classes.
     *
     * @param applicationIndex the index of the application root archive: only the application's own classes are listed
     * @param index the combined index, used to decide the SERVICE layer eligibility exactly as the build step does
     * @param rules the build-time targeting rules
     * @return one entry per guarded method, sorted by class then method name, or a single entry whose class and
     *         method are {@value #NONE} when no method is guarded
     */
    static List<Map<String, Object>> of(IndexView applicationIndex, IndexView index, TargetRules rules) {
        List<Map<String, Object>> inventory = new ArrayList<>();
        List<ClassInfo> classes = new ArrayList<>(applicationIndex.getKnownClasses());
        classes.sort(Comparator.comparing(clazz -> clazz.name().toString()));
        for (ClassInfo clazz : classes) {
            Map<DotName, AnnotationInstance> classLevel = guardingAnnotations(clazz.declaredAnnotations());
            List<MethodInfo> methods = new ArrayList<>(clazz.methods());
            methods.sort(Comparator.comparing(MethodInfo::name).thenComparing(MethodInfo::parametersCount));
            for (MethodInfo method : methods) {
                if (!isBusinessMethod(clazz, method)) {
                    continue;
                }
                Map<DotName, AnnotationInstance> effective = new LinkedHashMap<>();
                Map<DotName, AnnotationInstance> methodLevel = guardingAnnotations(method.declaredAnnotations());
                for (DotName name : ANNOTATIONS.keySet()) {
                    AnnotationInstance annotation = methodLevel.getOrDefault(name, classLevel.get(name));
                    if (annotation != null) {
                        effective.put(name, annotation);
                    }
                }
                if (!effective.isEmpty()) {
                    inventory.add(entry(clazz, method, effective, index, rules));
                }
            }
        }
        if (inventory.isEmpty()) {
            // Quarkus drops an empty build-time value from the resource list: one explicit entry keeps the resource
            // present, so "no guard" cannot be mistaken for a wrong resource name or a disabled Dev MCP server
            inventory.add(Map.of("class", NONE, "method", NONE, "note", NO_GUARD));
        }
        return inventory;
    }

    private static Map<String, Object> entry(ClassInfo clazz, MethodInfo method,
            Map<DotName, AnnotationInstance> annotations, IndexView index, TargetRules rules) {
        Map<String, Object> guards = new LinkedHashMap<>();
        annotations.forEach((name, annotation) -> guards.put(name.withoutPackagePrefix(),
                parameters(annotation, ANNOTATIONS.get(name))));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("class", clazz.name().toString());
        entry.put("method", method.name());
        entry.put("metricMethodTag", clazz.name() + "." + method.name());
        entry.put("annotations", guards);
        List<String> layers = reachingLayers(clazz, method, index, rules);
        entry.put("reachableBy", layers);
        if (layers.isEmpty()) {
            entry.put("note", unreachableReason(clazz));
        }
        return entry;
    }

    /**
     * The Goblin layers whose faults are raised <em>inside</em> the Fault Tolerance interceptor of the method, so its
     * annotations get to react. The DATABASE layer reaches a guarded method too when the method acquires a JDBC
     * connection, which an annotation index cannot tell: the playbook says so instead.
     */
    private static List<String> reachingLayers(ClassInfo clazz, MethodInfo method, IndexView index, TargetRules rules) {
        List<String> layers = new ArrayList<>();
        if (GoblinBuildStep.isMethodEligible(clazz, method, rules, index)) {
            layers.add("SERVICE");
        }
        if (clazz.isInterface() && clazz.declaredAnnotation(REGISTER_REST_CLIENT) != null) {
            layers.add("HTTP_OUT");
        }
        return layers;
    }

    private static String unreachableReason(ClassInfo clazz) {
        if (clazz.declaredAnnotation(DotName.createSimple("jakarta.ws.rs.Path")) != null) {
            return "JAX-RS resource: the HTTP_IN layer aborts the request before these annotations can react, and the "
                    + "SERVICE layer never weaves resources. Move the guard to a bean the resource calls to exercise it.";
        }
        return "Not woven by the SERVICE layer (quarkus.goblin.target rules, or a class that cannot be intercepted). "
                + "A DATABASE fault still reaches it if the method acquires a JDBC connection.";
    }

    private static Map<String, Object> parameters(AnnotationInstance annotation, Map<String, Object> defaults) {
        Map<String, Object> parameters = new LinkedHashMap<>(defaults);
        for (AnnotationValue value : annotation.values()) {
            if (parameters.containsKey(value.name())) {
                parameters.put(value.name(), toJson(value));
            }
        }
        return parameters;
    }

    private static Object toJson(AnnotationValue value) {
        return switch (value.kind()) {
            case ENUM -> value.asEnum();
            case CLASS -> value.asClass().name().toString();
            case ARRAY -> {
                List<Object> items = new ArrayList<>();
                switch (value.componentKind()) {
                    case CLASS -> Arrays.stream(value.asClassArray()).forEach(type -> items.add(type.name().toString()));
                    case ENUM -> items.addAll(Arrays.asList(value.asEnumArray()));
                    default -> items.addAll(Arrays.asList(value.asStringArray()));
                }
                yield items;
            }
            default -> value.value();
        };
    }

    private static Map<DotName, AnnotationInstance> guardingAnnotations(Iterable<AnnotationInstance> annotations) {
        Map<DotName, AnnotationInstance> guarding = new LinkedHashMap<>();
        for (AnnotationInstance annotation : annotations) {
            if (ANNOTATIONS.containsKey(annotation.name())) {
                guarding.put(annotation.name(), annotation);
            }
        }
        return guarding;
    }

    /**
     * A method a Fault Tolerance annotation can guard: declared, not a constructor or initializer, not synthetic, and,
     * on a class, neither static nor private. Interface methods (REST Client interfaces) are business methods too.
     */
    private static boolean isBusinessMethod(ClassInfo clazz, MethodInfo method) {
        if (method.isConstructor() || method.isStaticInitializer() || method.isSynthetic()) {
            return false;
        }
        if (Modifier.isStatic(method.flags())) {
            return false;
        }
        return clazz.isInterface() || !Modifier.isPrivate(method.flags());
    }

    private static Map<String, Object> defaults(Object... keysAndValues) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            defaults.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return defaults;
    }
}
