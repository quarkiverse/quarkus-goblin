package io.quarkiverse.goblin.deployment;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.inject.Default;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.GoblinLayerHooks;
import io.quarkiverse.goblin.GoblinLayerHooksCreator;
import io.quarkiverse.goblin.GoblinTargetingConfig;
import io.quarkiverse.goblin.TargetRules;
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
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.gizmo.Gizmo;

/**
 * Installs the optional layer hooks, each only when the application has the matching extension, so applications
 * without a datasource or without messaging never load the corresponding third-party API:
 * <ul>
 * <li>{@link ChaosLayer#DATABASE} -- one Agroal pool interceptor per JDBC datasource, and a call to it woven at the
 * start of Agroal's connection acquisition (capability {@code io.quarkus.agroal});</li>
 * <li>{@link ChaosLayer#MESSAGING} -- an interceptor bound to the application's {@code @Incoming} consumer methods
 * (capability {@code io.quarkus.messaging}).</li>
 * </ul>
 * Like the rest of the chaos wiring, nothing is installed in a production build.
 */
public class GoblinLayerHooksProcessor {

    private static final Logger LOG = Logger.getLogger(GoblinLayerHooksProcessor.class);

    static final String AGROAL_CONNECTION_POOL = "io.agroal.pool.ConnectionPool";
    static final String AGROAL_BEFORE_ACQUIRE = "beforeAcquire";

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
     * @param transformers producer for the transformation weaving the database hook into Agroal's pool
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void registerDatabaseHook(Capabilities capabilities, List<JdbcDataSourceBuildItem> dataSources,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans, BuildProducer<BytecodeTransformerBuildItem> transformers) {
        if (!capabilities.isPresent(Capability.AGROAL)) {
            return;
        }
        if (!dataSources.isEmpty()) {
            transformers.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(AGROAL_CONNECTION_POOL)
                    .setCacheable(true)
                    .setVisitorFunction((className, visitor) -> new BeforeAcquireHookVisitor(visitor))
                    .build());
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
     * @param targeting the build-time targeting rules
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void registerMessagingHook(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<AnnotationsTransformerBuildItem> transformers, ApplicationArchivesBuildItem applicationArchives,
            CombinedIndexBuildItem combinedIndex, GoblinTargetingConfig targeting) {
        TargetRules rules = TargetRules.of(targeting);
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
                            && GoblinBuildStep.isMethodEligible(clazz, method, rules, index);
                })
                .transform(context -> context.add(GoblinMessagingAssault.class));
        transformers.produce(new AnnotationsTransformerBuildItem(transformation));
    }

    /**
     * Exposes which optional layers are backed by an installed hook as the {@link GoblinLayerHooks} synthetic bean, so
     * the Dev UI can offer them and the per-request resolution never selects a layer that cannot fire.
     *
     * @param capabilities the application capabilities
     * @param dataSources the JDBC datasources of the application
     * @param syntheticBeans producer for the synthetic bean
     */
    @BuildStep(onlyIfNot = IsProduction.class)
    void declareOptionalHooks(Capabilities capabilities, List<JdbcDataSourceBuildItem> dataSources,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        List<String> hooks = new ArrayList<>();
        if (capabilities.isPresent(Capability.AGROAL) && !dataSources.isEmpty()) {
            hooks.add(ChaosLayer.DATABASE.name());
        }
        if (capabilities.isPresent(Capability.MESSAGING)) {
            hooks.add(ChaosLayer.MESSAGING.name());
        }
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(GoblinLayerHooks.class)
                .scope(Singleton.class)
                .unremovable()
                .param(GoblinLayerHooksCreator.PARAM_LAYERS, hooks.toArray(String[]::new))
                .creator(GoblinLayerHooksCreator.class)
                .done());
    }

    /**
     * Weaves {@code GoblinAgroalPoolInterceptor.beforeConnectionAcquire(this.interceptors)} at the start of
     * {@code ConnectionPool.beforeAcquire()}, the first step of every {@code getConnection()}: a fault thrown from there
     * leaves no connection checked out nor enlisted, so the pool accounting stays consistent. Agroal's own
     * {@code onConnectionAcquire} interceptor callback runs too late for that (see the interceptor's Javadoc).
     */
    static final class BeforeAcquireHookVisitor extends ClassVisitor {

        private boolean woven;

        BeforeAcquireHookVisitor(ClassVisitor visitor) {
            super(Gizmo.ASM_API_VERSION, visitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!AGROAL_BEFORE_ACQUIRE.equals(name) || !"()J".equals(descriptor)) {
                return visitor;
            }
            woven = true;
            return new MethodVisitor(Gizmo.ASM_API_VERSION, visitor) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    visitVarInsn(Opcodes.ALOAD, 0);
                    visitFieldInsn(Opcodes.GETFIELD, AGROAL_CONNECTION_POOL.replace('.', '/'), "interceptors",
                            "Ljava/util/List;");
                    visitMethodInsn(Opcodes.INVOKESTATIC, GOBLIN_AGROAL_POOL_INTERCEPTOR.toString().replace('.', '/'),
                            "beforeConnectionAcquire", "(Ljava/util/List;)V", false);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (!woven) {
                LOG.warnf("Goblin: %s.%s() not found, the DATABASE layer cannot inject faults with this Agroal version",
                        AGROAL_CONNECTION_POOL, AGROAL_BEFORE_ACQUIRE);
            }
            super.visitEnd();
        }
    }
}
