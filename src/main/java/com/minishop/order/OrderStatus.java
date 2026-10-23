package com.minishop.order;

/** Trạng thái đơn hàng. Quy tắc chuyển trạng thái ({@code canTransitionTo}) được thêm ở GĐ5. */
enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPING,
    DELIVERED,
    CANCELLED
}
