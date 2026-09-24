package io.quarkiverse.goblin;

import java.util.EnumSet;
import java.util.Set;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class GoblinRecorder {

    public void activate(GoblinConfig config) {
        AssaultEngine.setStaticConfig(config);
    }

    /**
     * Declares which optional layer hooks (DATABASE, MESSAGING) were installed at build time.
     *
     * @param layers the names of the installed optional layers
     */
    public void registerOptionalHooks(Set<String> layers) {
        Set<ChaosLayer> hooks = EnumSet.noneOf(ChaosLayer.class);
        layers.forEach(layer -> hooks.add(ChaosLayer.valueOf(layer)));
        AssaultEngine.setOptionalHooks(hooks);
    }
}
