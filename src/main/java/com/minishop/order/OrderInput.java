package com.minishop.order;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses raw request parameters for the order endpoints; a bad value must never become a 500. */
final class OrderInput {

    static final int MAX_PAGE = 10_000;

    private static final Pattern ORDER_ID = Pattern.compile("[0-9]{1,18}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,15}");
    private static final Pattern PAGE = Pattern.compile("[0-9]{1,9}");

    private OrderInput() {
    }

    static Optional<Long> orderId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        return ORDER_ID.matcher(s).matches() ? Optional.of(Long.valueOf(s)) : Optional.empty();
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

    /** The total the customer saw. Only compared, never stored. */
    static Optional<BigDecimal> amount(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        return AMOUNT.matcher(s).matches() ? Optional.of(new BigDecimal(s)) : Optional.empty();
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
