package io.quarkiverse.goblin.deployment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.Default;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.GoblinConfig;
import io.quarkiverse.goblin.GoblinRecorder;
import io.quarkiverse.goblin.database.GoblinAgroalPoolInterceptorCreator;
import io.quarkiverse.goblin.messaging.GoblinMessagingAssault;
import io.quarkiverse.goblin.messaging.GoblinMessagingInterceptor;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;

/**
 * Installs the optional layer hooks, each only when the application has the matching extension, so applications
 * without a datasource or without messaging never load the corresponding third-party API:
 * <ul>
 * <li>{@link ChaosLayer#DATABASE} -- one Agroal pool interceptor per JDBC datasource (capability
 * {@code io.quarkus.agroal});</li>
 * <li>{@link ChaosLayer#MESSAGING} -- an interceptor bound to the application's {@code @Incoming} consumer methods
 * (capability {@code io.quarkus.messaging}).</li>
 * </ul>
 * Like the rest of the chaos wiring, nothing is installed in a production build.
 */
public class GoblinLayerHooksProcessor {

    private static final DotName AGROAL_POOL_INTERCEPTOR = DotName.createSimple("io.agroal.api.AgroalPoolInterceptor");
    private static final DotName GOBLIN_AGROAL_POOL_INTERCEPTOR = DotName
            .createSimple("io.quarkiverse.goblin.database.GoblinAgroalPoolInterceptor");
    private static final DotName AGROAL_DATASOURCE_QUALIFIER = DotName.createSimple("io.quarkus.agroal.DataSource");
    private static final DotName INCOMING = DotName
            .createSimple("org.eclipse.microprofile.reactive.messaging.Incoming");
    private static final DotName INCOMINGS = DotName
            .createSimple("org.eclipse.microprofile.reactive.messaging.Incomings");

    /**
     * Registers one {@code GoblinAgroalPoolInterceptor} synthetic bean per JDBC datasource, qualified the way Agroal
     * selects the interceptors of a datasource ({@code @Default} for the default one, {@code @DataSource("name")}
     * otherwise).
     *
     * @param capabilities the application capabilities
     * @param dataSources the JDBC datasources of the application
     * @param syntheticBeans producer for the synthetic interceptor beans
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void registerDatabaseHook(Capabilities capabilities, List<JdbcDataSourceBuildItem> dataSources,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        if (!capabilities.isPresent(Capability.AGROAL)) {
            return;
        }
        for (JdbcDataSourceBuildItem dataSource : dataSources) {
            AnnotationInstance qualifier = dataSource.isDefault()
                    ? AnnotationInstance.builder(Default.class).build()
                    : AnnotationInstance.builder(AGROAL_DATASOURCE_QUALIFIER).value(dataSource.getName()).build();
            syntheticBeans.produce(SyntheticBeanBuildItem.configure(GOBLIN_AGROAL_POOL_INTERCEPTOR)
                    .addType(AGROAL_POOL_INTERCEPTOR)
                    .addQualifier(qualifier)
                    .scope(Singleton.class)
                    .unremovable()
                    .param(GoblinAgroalPoolInterceptorCreator.PARAM_DATASOURCE, dataSource.getName())
                    .creator(GoblinAgroalPoolInterceptorCreator.class)
                    .done());
        }
    }

    /**
     * Registers the messaging interceptor and binds it to every eligible {@code @Incoming} method of the application
     * (same {@code goblin.target} rules as the service layer).
     *
     * @param capabilities the application capabilities
     * @param additionalBeans producer for the interceptor bean
     * @param transformers producer for the annotation transformation
     * @param applicationArchives access to the application's root archive index
     * @param combinedIndex the combined index, used to resolve implemented interfaces
     * @param config the build-time Goblin configuration
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void registerMessagingHook(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<AnnotationsTransformerBuildItem> transformers, ApplicationArchivesBuildItem applicationArchives,
            CombinedIndexBuildItem combinedIndex, GoblinConfig config) {
        if (!capabilities.isPresent(Capability.MESSAGING)) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(GoblinMessagingInterceptor.class)
                .build());

        IndexView applicationIndex = applicationArchives.getRootArchive().getIndex();
        IndexView index = combinedIndex.getIndex();
        AnnotationTransformation transformation = AnnotationTransformation.forMethods()
                .whenMethod(method -> {
                    ClassInfo clazz = method.declaringClass();
                    return (method.hasDeclaredAnnotation(INCOMING) || method.hasDeclaredAnnotation(INCOMINGS))
                            && applicationIndex.getClassByName(clazz.name()) != null
                            && GoblinBuildStep.isMethodEligible(clazz, method, config, index);
                })
                .transform(context -> context.add(GoblinMessagingAssault.class));
        transformers.produce(new AnnotationsTransformerBuildItem(transformation));
    }

    /**
     * Tells the runtime engine which optional layers are backed by an installed hook, so the Dev UI can offer them and
     * the per-request resolution never selects a layer that cannot fire.
     *
     * @param recorder the Goblin recorder
     * @param capabilities the application capabilities
     * @param dataSources the JDBC datasources of the application
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void declareOptionalHooks(GoblinRecorder recorder, Capabilities capabilities,
            List<JdbcDataSourceBuildItem> dataSources) {
        Set<String> hooks = new HashSet<>();
        if (capabilities.isPresent(Capability.AGROAL) && !dataSources.isEmpty()) {
            hooks.add(ChaosLayer.DATABASE.name());
        }
        if (capabilities.isPresent(Capability.MESSAGING)) {
            hooks.add(ChaosLayer.MESSAGING.name());
        }
        recorder.registerOptionalHooks(hooks);
    }
}
