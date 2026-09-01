package io.quarkiverse.goblin.dev;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class GoblinRecorder {

    public void activate(GoblinConfig config) {
        AssaultEngine.setStaticConfig(config);
    }
}
