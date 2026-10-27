package com.minishop.cart;

/**
 * Cổng để {@code order} làm mới số trên nút giỏ sau khi đặt hàng (QĐ4 của D4): việc đặt hàng xóa giỏ DB trong
 * transaction của {@code order}, mà {@code cart} không biết. Bản cài đặt là {@code CartBadgePresenter}.
 * <p>Gọi trên luồng giao diện, sau một thay đổi giỏ mà {@code cart} không tự biết; số mới được đọc lại ở
 * luồng nền, và một lần làm mới cũ còn chờ không ghi đè được kết quả mới.
 */
public interface CartBadge {

    /** Đọc lại số lượng trong giỏ của phiên hiện tại và cập nhật nút giỏ. */
    void refresh();
}
