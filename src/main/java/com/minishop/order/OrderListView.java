package com.minishop.order;

import java.util.List;

record OrderListView(List<OrderSummaryView> orders, int page, int totalPages, long totalElements) {

    /** True when the user has no orders at all (not merely an empty page). Public: templates read it via SpEL. */
    public boolean isEmpty() {
        return totalElements == 0;
    }
}
