package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Màn "Đơn hàng" (danh sách, AC-006-38, -39, QĐ4 của D5). Route là nguồn sự thật của bộ lọc và trang.
 *
 * <p>{@code status} là {@code StatusFilterOption.value()}: tên enum ({@code PENDING}...) hoặc {@code null} cho
 * "Tất cả". Chuỗi rỗng hoặc toàn khoảng trắng thành {@code null}; giá trị lạ vẫn giữ nguyên vì service tự coi
 * nó là "Tất cả". Trang nhỏ hơn 1 thành 1. Màn truyền trang cho service dạng chuỗi.
 *
 * @param status tên trạng thái cần lọc, {@code null} nếu "Tất cả"
 * @param page   trang, đánh số từ 1
 */
record AdminOrdersRoute(String status, int page) implements Route {

    AdminOrdersRoute {
        if (status != null) {
            status = status.strip();
            if (status.isEmpty()) {
                status = null;
            }
        }
        if (page < 1) {
            page = 1;
        }
    }

    /** Mọi đơn, trang 1: lối vào của mục "Đơn hàng". */
    static AdminOrdersRoute all() {
        return new AdminOrdersRoute(null, 1);
    }

    /** Đổi bộ lọc ({@code null} là "Tất cả"); về trang 1. */
    AdminOrdersRoute withStatus(String newStatus) {
        return new AdminOrdersRoute(newStatus, 1);
    }

    /** Đổi trang, giữ bộ lọc. */
    AdminOrdersRoute withPage(int newPage) {
        return new AdminOrdersRoute(status, newPage);
    }
}
