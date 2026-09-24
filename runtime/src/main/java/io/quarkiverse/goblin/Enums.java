package io.quarkiverse.goblin;

import java.util.Locale;
import java.util.Optional;

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
}
