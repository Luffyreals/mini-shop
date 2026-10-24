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

    /**
     * Chữ trên nút đi tới trạng thái này (admin). {@code PENDING} không bao giờ là đích của một bước chuyển.
     *
     * @throws IllegalStateException với {@code PENDING}
     */
    String actionLabel() {
        return switch (this) {
            case PENDING -> throw new IllegalStateException("PENDING is never a transition target");
            case CONFIRMED -> "Xác nhận đơn";
            case SHIPPING -> "Bắt đầu giao hàng";
            case DELIVERED -> "Xác nhận đã giao";
            case CANCELLED -> "Hủy đơn";
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
