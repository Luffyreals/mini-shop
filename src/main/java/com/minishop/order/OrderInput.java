package com.minishop.order;

import java.util.Optional;
import java.util.regex.Pattern;

/** Parses raw text for the order services (status, page); a bad value never becomes an error. */
final class OrderInput {

    static final int MAX_PAGE = 10_000;

    private static final Pattern PAGE = Pattern.compile("[0-9]{1,9}");

    private OrderInput() {
    }

    /** A target or filter status: the exact enum name after strip (case-sensitive); anything else is empty. */
    static Optional<OrderStatus> status(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        for (OrderStatus status : OrderStatus.values()) {
            if (status.name().equals(s)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
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
