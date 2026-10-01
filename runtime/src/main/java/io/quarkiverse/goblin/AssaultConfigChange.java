package io.quarkiverse.goblin;

/**
 * A change of the assault configuration, as delivered to {@link AssaultObserver#onConfigChange(AssaultConfigChange)}:
 * the configuration before and after the change, both frozen, and when it was published.
 * <p>
 * Both configurations are read-only snapshots ({@link MutableAssaultConfig#snapshot()}): every getter reads the same
 * state, and every setter throws {@link UnsupportedOperationException}, so an observer can keep them as the exact record
 * of an attack -- to report it, or to replay it after a fix -- but can never change the live configuration through them.
 *
 * @param previous the configuration before the change
 * @param current the configuration after the change
 * @param timestamp when the change was published, in epoch milliseconds
 */
public record AssaultConfigChange(MutableAssaultConfig previous, MutableAssaultConfig current, long timestamp) {

    /**
     * @return a human-readable summary of the assaults enabled before the change, e.g.
     *         {@code "latency enabled (100 - 500 ms)"}
     */
    public String previousDescription() {
        return previous.describeAssaults();
    }

    /**
     * @return a human-readable summary of the assaults enabled after the change
     */
    public String currentDescription() {
        return current.describeAssaults();
    }
}
