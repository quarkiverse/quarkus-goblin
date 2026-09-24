package io.quarkiverse.goblin;

import java.util.EnumSet;
import java.util.Set;

import io.quarkus.arc.BeanCreator;
import io.quarkus.arc.SyntheticCreationalContext;

/**
 * Creates the synthetic {@link GoblinLayerHooks} bean from the layer names computed at build time.
 */
public class GoblinLayerHooksCreator implements BeanCreator<GoblinLayerHooks> {

    /**
     * Synthetic bean parameter carrying the installed layer names.
     */
    public static final String PARAM_LAYERS = "layers";

    @Override
    public GoblinLayerHooks create(SyntheticCreationalContext<GoblinLayerHooks> context) {
        Set<ChaosLayer> layers = EnumSet.noneOf(ChaosLayer.class);
        for (String name : (String[]) context.getParams().get(PARAM_LAYERS)) {
            layers.add(ChaosLayer.valueOf(name));
        }
        return new GoblinLayerHooks(layers);
    }
}
