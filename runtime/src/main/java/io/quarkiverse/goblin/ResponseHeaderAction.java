package io.quarkiverse.goblin;

/**
 * Action applied to a single response header by the response header assault.
 * <p>
 * {@link #SET} forces the header to be present with the configured value: if the header already exists its value(s) are
 * replaced, otherwise the header is added. {@link #REMOVE} deletes the header entirely when present.
 */
public enum ResponseHeaderAction {

    /**
     * Forces the header to be present with the configured value, replacing any existing value.
     */
    SET,

    /**
     * Removes the header from the response when present.
     */
    REMOVE
}
