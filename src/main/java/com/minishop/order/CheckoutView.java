package com.minishop.order;

import java.math.BigDecimal;
import java.util.List;

/** The checkout page: cart lines at current prices, their total, and whether every line can be ordered. */
record CheckoutView(List<CheckoutLineView> lines, BigDecimal total, boolean orderable) {

    boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * True when the total the customer saw is missing or differs from the current one. Uses
     * {@code compareTo}: {@code equals} treats 450000 and 450000.0 as different because of the scale.
     */
    boolean totalDiffersFrom(BigDecimal expected) {
        return expected == null || total.compareTo(expected) != 0;
    }
}
