package com.minishop.order;

import java.util.List;

record OrderListView(List<OrderSummaryView> orders, int page, int totalPages, long totalElements) {

    /** True when the user has no orders at all (not merely an empty page). Screens read it to show the empty state. */
    public boolean isEmpty() {
        return totalElements == 0;
    }
}
