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
    REMOVE;

    /**
     * Parses a user-provided action, tolerating case and surrounding whitespace. The historical {@code ADD} and
     * {@code OVERRIDE} labels are mapped to {@link #SET} so previously saved configurations keep working.
     *
     * @param raw the raw action, possibly {@code null} or blank
     * @return the matching action, or empty when blank or unknown
     */
    public static java.util.Optional<ResponseHeaderAction> parse(String raw) {
        if (raw != null && ("ADD".equalsIgnoreCase(raw.trim()) || "OVERRIDE".equalsIgnoreCase(raw.trim()))) {
            return java.util.Optional.of(SET);
        }
        return Enums.parse(ResponseHeaderAction.class, raw);
    }
}
