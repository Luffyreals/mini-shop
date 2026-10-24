package com.minishop.user;

import com.minishop.common.FormText;

import java.util.Locale;

/**
 * The single place that defines how an email is normalised (BR-003-1): cleaned by {@link FormText#clean} (same as
 * {@link RegistrationForm}, so registration and login compare the very same string) and lower-cased.
 * {@link Locale#ROOT} avoids locale-sensitive surprises such as the Turkish dotted/dotless i.
 */
final class EmailNormalizer {

    private EmailNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return FormText.clean(raw, false).toLowerCase(Locale.ROOT);
    }
}
