package io.quarkiverse.goblin.runtime;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class GoblinRecorder {

    public void activate(GoblinConfig config) {
        AssaultEngine.setStaticConfig(config);
    }
}
