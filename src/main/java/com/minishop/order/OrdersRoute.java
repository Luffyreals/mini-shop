package com.minishop.order;

import com.minishop.desktop.Route;

/**
 * Màn "Đơn hàng của tôi" (QĐ2 của D4).
 *
 * @param page trang danh sách, tính từ 1; trang vượt cuối thì service trả về trang 1
 */
record OrdersRoute(int page) implements Route {

    OrdersRoute {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
    }

    /** Trang đầu, nơi mọi lối vào "Đơn hàng của tôi" bắt đầu. */
    static OrdersRoute first() {
        return new OrdersRoute(1);
    }
}
