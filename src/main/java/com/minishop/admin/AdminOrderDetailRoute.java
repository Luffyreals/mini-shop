package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Chi tiết một đơn trong khu quản trị (AC-006-40 đến -45, QĐ4 của D5).
 *
 * @param orderId id đơn
 * @param back    trang danh sách để quay về khi bấm "Quay lại danh sách đơn" và khi không tìm thấy đơn
 *                (AC-009-13), giữ đúng bộ lọc và trang; {@code null} thành {@link AdminOrdersRoute#all()}
 */
record AdminOrderDetailRoute(long orderId, AdminOrdersRoute back) implements Route {

    AdminOrderDetailRoute {
        if (back == null) {
            back = AdminOrdersRoute.all();
        }
    }
}
