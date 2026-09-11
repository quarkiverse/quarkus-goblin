package io.quarkiverse.goblin.assault;

import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.MutableAssaultConfig;

/**
 * Contract for a single chaos assault type.
 * <p>
 * Implementations declare the {@link AssaultType} they represent, whether they are enabled for the current
 * {@link MutableAssaultConfig}, the label used in the assault history and Markdown report, and their position in the
 * execution chain via {@link #order()}. When enabled, the filter applies assaults in ascending {@code order()}
 * sequence; a type that aborts the request should return {@link AssaultOutcome#ABORTED}.
 * <p>
 * Implementations must be {@code @ApplicationScoped} beans: they are discovered automatically at build time, no
 * manual registration is required.
 */
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
     * @param config the mutable configuration holding every assault toggle
     * @return {@code true} to run this assault on eligible requests
     */
    boolean isEnabled(MutableAssaultConfig config);

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