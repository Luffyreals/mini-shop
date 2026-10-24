package com.minishop.order;

import java.util.List;

/** Một trang đơn của admin. {@code status} null nghĩa là "Tất cả". */
public record AdminOrderPage(List<AdminOrderRow> orders, String status, String statusLabel,
                             List<StatusFilterOption> filters, int page, int totalPages, long totalElements) {

    /** True khi không có đơn nào khớp bộ lọc (không chỉ là trang rỗng). Public: template đọc qua SpEL. */
    public boolean isEmpty() {
        return totalElements == 0;
    }
}
