package io.quarkiverse.goblin.database;

import java.sql.Connection;
import java.util.List;

import io.agroal.api.AgroalPoolInterceptor;
import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.ChaosLayer;
import io.quarkiverse.goblin.ChaosRequestContext;
import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkiverse.goblin.assault.LayerFaults;
import io.quarkus.arc.Arc;

/**
 * Agroal pool interceptor backing the {@link ChaosLayer#DATABASE} layer: when the current request resolved to the
 * database layer, latency and exception assaults are injected when the application asks the pool for a JDBC
 * connection, i.e. exactly where a slow or unreachable database surfaces in a real application -- below Hibernate ORM,
 * Panache and plain JDBC code alike.
 * <p>
 * The fault fires from {@link #beforeConnectionAcquire(List)}, which the deployment processor calls at the very start
 * of Agroal's connection acquisition, <em>before</em> any pooled connection is checked out or enlisted in the
 * transaction: the exception propagates out of {@code getConnection()} and the pool is left exactly as it was. The
 * {@link AgroalPoolInterceptor#onConnectionAcquire(Connection)} callback is deliberately not used: Agroal invokes it
 * once the connection is checked out and enlisted, and an exception thrown from there leaves the connection enlisted
 * in the transaction while back in the pool, which destroys it at the end of the transaction ("Closing connection in
 * incorrect state CHECKED_IN") and never decrements the active-connection metric.
 * <p>
 * Every {@code getConnection()} call of a request is a candidate: the first one uses the per-request decision, every
 * further one (typically a {@code @Retry} attempt opening a new transaction) draws the target level again. This
 * interceptor instance is how a datasource opts in: a pool without it is never assaulted, and its datasource name is
 * the one recorded in the assault history.
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

    /**
     * Entry point woven at the start of Agroal's connection acquisition ({@code ConnectionPool.beforeAcquire()}):
     * assaults the acquisition when the pool carries a Goblin interceptor.
     *
     * @param interceptors the interceptors of the pool the connection is requested from, possibly {@code null}
     */
    public static void beforeConnectionAcquire(List<? extends AgroalPoolInterceptor> interceptors) {
        if (interceptors == null || !ChaosRequestContext.is(ChaosLayer.DATABASE)) {
            return;
        }
        for (AgroalPoolInterceptor interceptor : interceptors) {
            if (interceptor instanceof GoblinAgroalPoolInterceptor goblin) {
                goblin.assaultAcquisition();
                return;
            }
        }
    }

    /**
     * Injects the database-layer faults into a connection acquisition when the level gate lets them fire.
     */
    void assaultAcquisition() {
        AssaultEngine engine = Arc.container().instance(AssaultEngine.class).get();
        MutableAssaultConfig cfg = engine.configSnapshot();
        if (cfg == null || !engine.isActive() || !LayerFaults.shouldFire(engine)) {
            return;
        }
        try {
            LayerFaults.inject(engine, cfg, AssaultSource.DATABASE, describe());
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
