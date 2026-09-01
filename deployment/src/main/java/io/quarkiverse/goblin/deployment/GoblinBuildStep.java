package io.quarkiverse.goblin.deployment;

import io.quarkiverse.goblin.GoblinConfig;
import io.quarkiverse.goblin.GoblinRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
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
    @Record(ExecutionTime.RUNTIME_INIT)
    void activateChaos(GoblinRecorder recorder, GoblinConfig config, LaunchModeBuildItem launchMode) {
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT
                || launchMode.getLaunchMode() == LaunchMode.TEST) {
            recorder.activate(config);
        }
    }
}
