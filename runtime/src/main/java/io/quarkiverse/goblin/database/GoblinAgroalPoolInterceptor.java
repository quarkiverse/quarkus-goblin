package io.quarkiverse.goblin.database;

import java.sql.Connection;

import io.agroal.api.AgroalPoolInterceptor;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.assault.LayerFaults;
import io.quarkus.arc.Arc;

/**
 * Agroal pool interceptor backing the {@link ChaosLayer#DATABASE} layer: when the current request resolved to the
 * database layer, latency and exception assaults are injected at JDBC connection acquisition, i.e. exactly where a slow
 * or unreachable database surfaces in a real application -- below Hibernate ORM, Panache and plain JDBC code alike.
 * <p>
 * Agroal invokes {@link #onConnectionAcquire(Connection)} once per {@code getConnection()} outside a transaction, and once
 * per transaction inside one. The first acquisition of a request uses the per-request decision; every further
 * acquisition (typically a {@code @Retry} attempt opening a new transaction) draws the target level again. A thrown
 * exception releases the connection back to the pool and propagates out of {@code getConnection()}.
 * <p>
 * Deliberately not a CDI bean class: the deployment processor registers one synthetic bean per datasource (with the
 * datasource qualifier Agroal selects interceptors with), and only when {@code quarkus-agroal} is present, so
 * applications without a datasource never load the Agroal API.
 */
public class GoblinAgroalPoolInterceptor implements AgroalPoolInterceptor {

    private final String dataSourceName;

    /**
     * @param dataSourceName the name of the intercepted datasource, used in the assault history
     */
    public GoblinAgroalPoolInterceptor(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    @Override
    public void onConnectionAcquire(Connection connection) {
        if (!ChaosRequestContext.is(ChaosLayer.DATABASE)) {
            return;
        }
        AssaultEngine engine = Arc.container().instance(AssaultEngine.class).get();
        MutableAssaultConfig cfg = engine.getMutableConfig();
        if (cfg == null || !engine.isActive() || !LayerFaults.shouldFire(engine)) {
            return;
        }
        try {
            LayerFaults.inject(engine, cfg, describe());
        } catch (InterruptedException e) {
            // interrupt flag restored by LatencySupport; surface it as an acquisition failure
            throw new IllegalStateException("Goblin: database latency assault interrupted", e);
        }
    }

    /**
     * @return the history identifier of this datasource, e.g. {@code "Database <default> connection"}
     */
    String describe() {
        return "Database " + dataSourceName + " connection";
    }
}
