package com.minishop.order;

/**
 * Trạng thái đơn hàng và luồng chuyển trạng thái.
 *
 * <pre>
 * PENDING   -> CONFIRMED, CANCELLED
 * CONFIRMED -> SHIPPING,  CANCELLED
 * SHIPPING  -> DELIVERED
 * DELIVERED, CANCELLED: trạng thái cuối
 * </pre>
 *
 * <p>Đây là bảng chung (admin dùng ở GĐ6). Khách chỉ hủy được khi {@code PENDING}:
 * quy tắc riêng đó nằm trong {@code OrderService}, không nằm ở đây.
 */
enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPING,
    DELIVERED,
    CANCELLED;

    /** {@code switch} không có {@code default}: thêm trạng thái mới mà quên xử lý sẽ lỗi biên dịch. */
    boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == SHIPPING || next == CANCELLED;
            case SHIPPING -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }

    /** Nhãn hiển thị tiếng Việt (BR-005-9). */
    String label() {
        return switch (this) {
            case PENDING -> "Chờ xử lý";
            case CONFIRMED -> "Đã xác nhận";
            case SHIPPING -> "Đang giao";
            case DELIVERED -> "Đã giao";
            case CANCELLED -> "Đã hủy";
        };
    }
}
