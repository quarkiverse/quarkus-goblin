package io.quarkiverse.goblin.assault;

/**
 * Result of an {@link Assault#apply(AssaultContext)} invocation, telling the execution chain how to proceed.
 * <p>
 * Part of the public API covered by semantic versioning. Like the other public enums, it may gain constants in a minor
 * version.
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