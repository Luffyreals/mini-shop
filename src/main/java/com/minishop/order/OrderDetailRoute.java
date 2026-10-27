package com.minishop.order;

import com.minishop.desktop.Route;

/**
 * Màn chi tiết một đơn hàng (QĐ2 của D4).
 *
 * @param orderId    id đơn
 * @param back       trang danh sách để quay về khi bấm "Quay lại danh sách đơn" hoặc khi không tìm thấy đơn
 *                   (AC-009-13); {@code null} thành {@link OrdersRoute#first()}
 * @param justPlaced {@code true} khi vừa đặt xong: màn hiện dòng "Đặt hàng thành công. Mã đơn của bạn là #{id}."
 */
record OrderDetailRoute(long orderId, OrdersRoute back, boolean justPlaced) implements Route {

    OrderDetailRoute {
        if (back == null) {
            back = OrdersRoute.first();
        }
    }
}
