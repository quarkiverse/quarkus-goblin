package io.quarkiverse.goblin.deployment;

import java.lang.reflect.Modifier;
import java.util.Optional;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import io.quarkiverse.goblin.GoblinChaosClientFilter;
import io.quarkiverse.goblin.GoblinConfig;
import io.quarkiverse.goblin.GoblinRecorder;
import io.quarkiverse.goblin.assault.Assault;
import io.quarkiverse.goblin.service.GoblinServiceAssault;
import io.quarkiverse.goblin.service.GoblinServiceInterceptor;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;

public class GoblinBuildStep {

    static final String FEATURE = "goblin";

    private static final DotName MICROPROFILE_FALLBACK = DotName.createSimple(
            "org.eclipse.microprofile.faulttolerance.Fallback");

    /**
     * Packages owned by the extension itself. Beans living there are never decorated with the service assault binding,
     * so incidental calls to Goblin's own beans inside an armed request are never assaulted themselves. The package
     * boundary is exact for the root package (application classes following the {@code io.quarkiverse.goblin.*} style,
     * e.g. the integration test app) must keep being eligible.
     */
    private static final String[] INTERNAL_PACKAGE_PREFIXES = {
            "io.quarkiverse.goblin.assault",
            "io.quarkiverse.goblin.service",
            "io.quarkiverse.goblin.dev",
            "io.quarkiverse.goblin.metrics",
            "io.quarkiverse.goblin.opentelemetry"
    };

    private static final DotName JAX_RS_PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName JAX_RS_PROVIDER = DotName.createSimple("jakarta.ws.rs.ext.Provider");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerAssaultBeans(CombinedIndexBuildItem combinedIndex,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder().setUnremovable();
        DotName assaultName = DotName.createSimple(Assault.class.getName());
        for (ClassInfo ci : combinedIndex.getIndex().getAllKnownImplementations(assaultName)) {
            builder.addBeanClass(ci.name().toString());
        }
        additionalBeans.produce(builder.build());
    }

    /**
     * Registers the client-side chaos filter as an unremovable bean so the REST Client extension picks it up as a
     * global {@code ClientRequestFilter} provider and applies it to every outbound MicroProfile REST Client call.
     *
     * @param additionalBeans producer for additional bean registrations
     */
    @BuildStep
    void registerClientFilterBean(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(GoblinChaosClientFilter.class)
                .build());
    }

    /**
     * Registers the service-layer assault interceptor as an unremovable bean and decorates every eligible application
     * bean class at build time with the {@link GoblinServiceAssault} binding (honouring the
     * {@code goblin.target.include-packages / exclude-packages / exclude-annotations} rules), so the interceptor kicks
     * in without any annotation in application code. Only classes Arc turns into beans are ever intercepted by CDI, so
     * decorating non-beans is a cheap no-op.
     * <p>
     * The transform is strictly limited to classes of the user's own application (the root archive): extension and
     * third-party runtime classes never receive the binding, which keeps library beans (e.g. OpenTelemetry producers)
     * out of the assault paths and avoids recursions such as an intercepted span tracer feeding the assault history.
     * <p>
     * The binding is produced on each eligible method rather than on the class so that methods referenced by
     * {@code @Fallback(fallbackMethod = ...)} can be spared: a fallback is invoked through the CDI proxy, so a decorated
     * fallback would be assaulted a second time and never get to answer the original failure.
     *
     * @param additionalBeans producer for additional bean registrations
     * @param transformers producer for annotation transformations
     * @param applicationArchives access to the application's root archive index
     * @param config the build-time Goblin configuration
     */
    @BuildStep
    void registerServiceInterceptor(BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<AnnotationsTransformerBuildItem> transformers,
            ApplicationArchivesBuildItem applicationArchives, GoblinConfig config) {
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(GoblinServiceInterceptor.class)
                .build());

        IndexView applicationIndex = applicationArchives.getRootArchive().getIndex();
        AnnotationTransformation transformation = AnnotationTransformation.forMethods()
                .whenMethod(method -> {
                    ClassInfo clazz = method.declaringClass();
                    return applicationIndex.getClassByName(clazz.name()) != null
                            && isMethodEligible(clazz, method, config);
                })
                .transform(context -> context.add(GoblinServiceAssault.class));
        transformers.produce(new AnnotationsTransformerBuildItem(transformation));
    }

    /**
     * Decides whether an application bean method is eligible for service-layer assaults, mirroring the runtime
     * {@code goblin.target} rules:
     * <ul>
     * <li>classes owned by the extension itself are never decorated;</li>
     * <li>interfaces, annotations, enums and JAX-RS resource/{@code @Provider} classes are never decorated: HTTP
     * classes are the HTTP_IN layer, and fault-tolerance-guarded business methods always sit <em>below</em> the
     * resource boundary -- intercepting a resource method would abort the request before the protected bean is
     * reached;</li>
     * <li>classes in a {@code goblin.target.exclude-packages} prefix are never decorated;</li>
     * <li>when {@code goblin.target.include-packages} is set, only matching classes are decorated;</li>
     * <li>classes carrying one of the {@code goblin.target.exclude-annotations} markers are never decorated;</li>
     * <li>static and private methods are never decorated, exactly as CDI interceptors ignore them;</li>
     * <li>methods referenced as {@code fallbackMethod} by a {@code @Fallback} in the same class are never decorated, so
     * the fallback can answer the original failure instead of being assaulted itself.</li>
     * </ul>
     *
     * @param clazz the declaring class to evaluate
     * @param method the method to evaluate
     * @param config the build-time Goblin configuration
     * @return {@code true} when the service assault binding should be added to the method
     */
    private static boolean isMethodEligible(ClassInfo clazz, MethodInfo method, GoblinConfig config) {
        if (!isServiceEligible(clazz, config)) {
            return false;
        }
        return !Modifier.isStatic(method.flags())
                && !Modifier.isPrivate(method.flags())
                && !isFallbackTargetMethod(clazz, method.name());
    }

    /**
     * Checks whether a method name is declared as the {@code fallbackMethod} of a {@code @Fallback} annotation of the
     * same class.
     *
     * @param clazz the class to scan for {@code @Fallback} annotations
     * @param methodName the candidate method name
     * @return {@code true} when the method is a fault-tolerance fallback target
     */
    private static boolean isFallbackTargetMethod(ClassInfo clazz, String methodName) {
        for (MethodInfo candidate : clazz.methods()) {
            AnnotationInstance fallback = candidate.declaredAnnotation(MICROPROFILE_FALLBACK);
            if (fallback != null) {
                AnnotationValue fallbackMethod = fallback.value("fallbackMethod");
                if (fallbackMethod != null && methodName.equals(fallbackMethod.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decides whether an application bean class is eligible for service-layer assaults, mirroring the runtime
     * {@code goblin.target} rules:
     * <ul>
     * <li>classes owned by the extension itself are never decorated;</li>
     * <li>interfaces, annotations, enums and JAX-RS resource/{@code @Provider} classes are never decorated: HTTP
     * classes are the HTTP_IN layer, and fault-tolerance-guarded business methods always sit <em>below</em> the
     * resource boundary -- intercepting a resource method would abort the request before the protected bean is
     * reached;</li>
     * <li>classes in a {@code goblin.target.exclude-packages} prefix are never decorated;</li>
     * <li>when {@code goblin.target.include-packages} is set, only matching classes are decorated;</li>
     * <li>classes carrying one of the {@code goblin.target.exclude-annotations} markers are never decorated.</li>
     * </ul>
     *
     * @param clazz the class to evaluate
     * @param config the build-time Goblin configuration
     * @return {@code true} when the service assault binding can be added to methods of the class
     */
    private static boolean isServiceEligible(ClassInfo clazz, GoblinConfig config) {
        String name = clazz.name().toString();
        if (name.lastIndexOf('.') < 0) {
            return false;
        }
        if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) {
            return false;
        }
        if (clazz.declaredAnnotation(JAX_RS_PATH) != null || clazz.declaredAnnotation(JAX_RS_PROVIDER) != null) {
            return false;
        }
        String packageName = name.substring(0, name.lastIndexOf('.'));
        if (isInternalPackage(packageName)) {
            return false;
        }
        if (matchesAnyPrefix(packageName, config.target().excludePackages())) {
            return false;
        }
        if (config.target().includePackages().isPresent() && config.target().includePackages().get().length > 0
                && !matchesAnyPrefix(packageName, config.target().includePackages())) {
            return false;
        }
        if (config.target().excludeAnnotations().isPresent()) {
            for (String excluded : config.target().excludeAnnotations().get()) {
                if (clazz.declaredAnnotation(DotName.createSimple(excluded)) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isInternalPackage(String packageName) {
        if ("io.quarkiverse.goblin".equals(packageName)) {
            return true;
        }
        for (String prefix : INTERNAL_PACKAGE_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyPrefix(String packageName, Optional<String[]> prefixes) {
        if (prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes.get()) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void activateChaos(GoblinRecorder recorder, GoblinConfig config, LaunchModeBuildItem launchMode) {
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT
                || launchMode.getLaunchMode() == LaunchMode.TEST) {
            recorder.activate(config);
        }
    }
}
