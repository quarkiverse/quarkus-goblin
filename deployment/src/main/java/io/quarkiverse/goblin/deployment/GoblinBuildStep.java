package io.quarkiverse.goblin.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkiverse.goblin.GoblinConfig;
import io.quarkiverse.goblin.GoblinRecorder;
import io.quarkiverse.goblin.assault.Assault;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;

public class GoblinBuildStep {

    static final String FEATURE = "goblin";

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

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void activateChaos(GoblinRecorder recorder, GoblinConfig config, LaunchModeBuildItem launchMode) {
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT
                || launchMode.getLaunchMode() == LaunchMode.TEST) {
            recorder.activate(config);
        }
    }
}
