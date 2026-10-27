package com.minishop.admin;

import com.minishop.common.FormText;

import java.util.Optional;
import java.util.regex.Pattern;

/** Parses raw text typed in the admin screens; a bad value must never become an unexpected error. */
final class AdminInput {

    static final int MAX_DELTA = 100_000;

    private static final Pattern DELTA = Pattern.compile("[+-]?[0-9]{1,6}");

    private AdminInput() {
    }

    /**
     * A stock adjustment: optional sign, 1-6 digits, not zero, at most 100.000 in absolute value. The text is cleaned
     * first (special spaces become spaces, invisible characters are dropped, then strip), so a pasted non-breaking
     * space does not make a valid number fail. Thousands separators are not accepted.
     */
    static Optional<Integer> stockDelta(String raw) {
        String s = FormText.clean(raw, false);
        if (s == null || !DELTA.matcher(s).matches()) {
            return Optional.empty();
        }
        int delta = Integer.parseInt(s); // parseInt accepts a leading '+'
        return delta == 0 || Math.abs(delta) > MAX_DELTA ? Optional.empty() : Optional.of(delta);
    }
}
