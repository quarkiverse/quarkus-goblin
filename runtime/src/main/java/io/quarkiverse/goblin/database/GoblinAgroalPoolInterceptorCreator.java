package io.quarkiverse.goblin.database;

import io.quarkus.arc.BeanCreator;
import io.quarkus.arc.SyntheticCreationalContext;

/**
 * Creates the synthetic {@link GoblinAgroalPoolInterceptor} bean registered for each datasource.
 */
public class GoblinAgroalPoolInterceptorCreator implements BeanCreator<GoblinAgroalPoolInterceptor> {

    /**
     * Synthetic bean parameter carrying the datasource name.
     */
    public static final String PARAM_DATASOURCE = "datasource";

    @Override
    public GoblinAgroalPoolInterceptor create(SyntheticCreationalContext<GoblinAgroalPoolInterceptor> context) {
        return new GoblinAgroalPoolInterceptor((String) context.getParams().get(PARAM_DATASOURCE));
    }
}
