package io.quarkiverse.goblin.assault;

/**
 * Result of an {@link Assault#apply(AssaultContext)} invocation, telling the execution chain how to proceed.
 */
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