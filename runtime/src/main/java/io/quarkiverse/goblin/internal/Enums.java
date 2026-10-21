package io.quarkiverse.goblin.internal;

import java.util.Locale;
import java.util.Optional;

import io.quarkiverse.goblin.ResponseHeaderAction;

/**
 * Tolerant parsing of enum values coming from users (state file, Dev UI, JSON-RPC): case-insensitive, surrounding
 * whitespace ignored. Each caller keeps its own policy for a missing or unknown value (default, error, skip).
 */
public final class Enums {

    private Enums() {
    }

    /**
     * Parses a user-provided enum value.
     *
     * @param type the enum type
     * @param raw the raw value, possibly {@code null} or blank
     * @param <E> the enum type
     * @return the matching constant, or empty when the value is blank or unknown
     */
    public static <E extends Enum<E>> Optional<E> parse(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Parses a user-provided response header action, like {@link #parse(Class, String)}. The historical {@code ADD} and
     * {@code OVERRIDE} labels are mapped to {@link ResponseHeaderAction#SET} so previously saved configurations keep
     * working.
     *
     * @param raw the raw action, possibly {@code null} or blank
     * @return the matching action, or empty when blank or unknown
     */
    public static Optional<ResponseHeaderAction> parseHeaderAction(String raw) {
        if (raw != null && ("ADD".equalsIgnoreCase(raw.trim()) || "OVERRIDE".equalsIgnoreCase(raw.trim()))) {
            return Optional.of(ResponseHeaderAction.SET);
        }
        return parse(ResponseHeaderAction.class, raw);
    }
}
