package io.quarkiverse.goblin.assault;

import io.quarkiverse.goblin.AssaultConfig;
import io.quarkiverse.goblin.AssaultType;
import io.smallrye.common.annotation.Experimental;

/**
 * Contract for a single chaos assault type.
 * <p>
 * Implementations declare the {@link AssaultType} they represent, whether they are enabled for the current
 * {@link AssaultConfig}, the label used in the assault history and Markdown report, and their position in the
 * execution chain via {@link #order()}. When enabled, the filter applies assaults in ascending {@code order()}
 * sequence; a type that aborts the request should return {@link AssaultOutcome#ABORTED}.
 * <p>
 * Custom assaults are applied by the JAX-RS filter only: reactive routes ({@code @Route}) apply the built-in assault
 * types and never call an {@code Assault} bean.
 * <p>
 * An assault only runs on a request that passes the per-request gate: chaos active, the {@code HTTP_IN} layer armed and
 * drawn for this request, and at least one built-in server-side assault enabled
 * ({@link AssaultConfig#hasAnyAssaultEnabled()}). A custom assault does not open the gate on its own: pair it with a
 * built-in toggle, or let {@link #isEnabled(AssaultConfig)} follow one.
 * <p>
 * Implementations are discovered automatically at build time, no manual registration is required. They are
 * {@code @ApplicationScoped} beans: a class without a scope annotation gets that scope.
 * <p>
 * Experimental: custom assaults run on the inbound JAX-RS filter only, so this SPI may change in a minor version when
 * another layer supports them. The rest of the public API is stable.
 */
@Experimental("Custom assaults are bound to JAX-RS; the SPI may change once another layer supports them")
public interface Assault {

    /**
     * The {@link AssaultType} this implementation represents.
     *
     * @return the assault type
     */
    AssaultType type();

    /**
     * Whether this assault should run given the current configuration.
     *
     * @param config a read-only snapshot of the configuration for the current request
     * @return {@code true} to run this assault on eligible requests
     */
    boolean isEnabled(AssaultConfig config);

    /**
     * The label recorded in the assault history and the exported Markdown report.
     *
     * @return a stable, human-readable label
     */
    String recordLabel();

    /**
     * Position of this assault in the execution chain, applied in ascending order.
     *
     * @return the execution order
     */
    int order();

    /**
     * Performs the assault on the current request.
     *
     * @param context the current assault context, holding the request, configuration, engine and target method
     * @return {@link AssaultOutcome#CONTINUE} to let the next enabled assault run, or {@link AssaultOutcome#ABORTED}
     *         to short-circuit the execution chain
     */
    AssaultOutcome apply(AssaultContext context);
}
