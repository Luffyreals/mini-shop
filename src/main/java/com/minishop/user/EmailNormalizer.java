package com.minishop.user;

import java.util.Locale;

/**
 * The single place that defines how an email is normalised (BR-003-1): trimmed and lower-cased.
 * {@link Locale#ROOT} avoids locale-sensitive surprises such as the Turkish dotted/dotless i.
 */
final class EmailNormalizer {

    private EmailNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }
}
