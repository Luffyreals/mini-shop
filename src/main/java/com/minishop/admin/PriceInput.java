package com.minishop.admin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses a price typed by the admin (BR-006-5): whole VND from 1 to 999.999.999, as plain digits without a leading zero, or with dots,
 * or with spaces as thousands separators in groups of three; the two separators are never mixed.
 */
final class PriceInput {

    static final long MAX = 999_999_999L;

    // No leading zero anywhere: "007" and "0.001" are rejected rather than silently read as 7 and 1.
    private static final Pattern PLAIN = Pattern.compile("[1-9][0-9]*");
    private static final Pattern DOTS = Pattern.compile("[1-9][0-9]{0,2}(\\.[0-9]{3})+");
    private static final Pattern SPACES = Pattern.compile("[1-9][0-9]{0,2}( [0-9]{3})+");

    private PriceInput() {
    }

    static Optional<BigDecimal> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        // No-break space, narrow no-break space and figure space (pasted from the web or Excel) count as a normal
        // space (BR-007-8). This must come before strip(), which does not treat U+00A0 as whitespace.
        String s = raw.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').strip();
        if (!(PLAIN.matcher(s).matches() || DOTS.matcher(s).matches() || SPACES.matcher(s).matches())) {
            return Optional.empty();
        }
        String digits = s.replace(".", "").replace(" ", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return Optional.empty();
        }
        long value = Long.parseLong(digits);
        return value >= 1 && value <= MAX ? Optional.of(BigDecimal.valueOf(value)) : Optional.empty();
    }
}
