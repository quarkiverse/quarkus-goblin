package io.quarkiverse.goblin.assault;

import io.smallrye.common.annotation.Experimental;

/**
 * Result of an {@link Assault#apply(AssaultContext)} invocation, telling the execution chain how to proceed.
 * <p>
 * Experimental: custom assaults run on the inbound JAX-RS filter only, so this SPI may change in a minor version when
 * another layer supports them. The rest of the public API is stable.
 */
@Experimental("Custom assaults are bound to JAX-RS; the SPI may change once another layer supports them")
public enum AssaultOutcome {

    /**
     * The assault completed without aborting: let the next enabled assault run.
     */
    CONTINUE,

    /**
     * The assault short-circuited the request (aborted or threw): stop processing further assaults.
     */
    ABORTED
}