package io.quarkiverse.goblin.internal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import io.quarkiverse.goblin.ChaosLayer;

/**
 * The optional layer hooks installed at build time for this application ({@link ChaosLayer#DATABASE} when a JDBC
 * datasource exists, {@link ChaosLayer#MESSAGING} with Quarkus Messaging). Exposed as a synthetic bean by the
 * deployment processor -- never in a production build -- and consumed by {@link AssaultEngineImpl}.
 */
public final class GoblinLayerHooks {

    private final Set<ChaosLayer> layers;

    /**
     * @param layers the layers whose optional hook is installed
     */
    public GoblinLayerHooks(Set<ChaosLayer> layers) {
        this.layers = layers.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(layers));
    }

    /**
     * @return the layers whose optional hook is installed
     */
    public Set<ChaosLayer> layers() {
        return layers;
    }
}
