package com.minishop.admin;

import java.util.Optional;
import java.util.regex.Pattern;

/** Parses raw request parameters for the admin endpoints; a bad value must never become a 500. */
final class AdminInput {

    static final int MAX_PAGE = 10_000;
    static final int MAX_DELTA = 100_000;

    private static final Pattern ID = Pattern.compile("[0-9]{1,18}");
    private static final Pattern PAGE = Pattern.compile("[0-9]{1,9}");
    private static final Pattern DELTA = Pattern.compile("[+-]?[0-9]{1,6}");

    private AdminInput() {
    }

    static Optional<Long> id(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        return ID.matcher(s).matches() ? Optional.of(Long.valueOf(s)) : Optional.empty();
    }

    /** A stock adjustment: optional sign, 1-6 digits, not zero, at most 100.000 in absolute value. */
    static Optional<Integer> stockDelta(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (!DELTA.matcher(s).matches()) {
            return Optional.empty();
        }
        int delta = Integer.parseInt(s); // parseInt accepts a leading '+'
        return delta == 0 || Math.abs(delta) > MAX_DELTA ? Optional.empty() : Optional.of(delta);
    }

    /** 1-based page, clamped to 1..{@link #MAX_PAGE}; anything invalid is page 1. */
    static int page(String raw) {
        if (raw == null) {
            return 1;
        }
        String s = raw.strip();
        if (!PAGE.matcher(s).matches()) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_PAGE, Integer.parseInt(s)));
    }
}
