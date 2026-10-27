package com.minishop.desktop;

import java.util.Objects;

/**
 * Nút hành động trong một toast, ví dụ "Xem giỏ hàng" sau "Đã thêm vào giỏ hàng." (AC-004-2). Bấm nút thì chạy
 * {@code action} (trên luồng giao diện) rồi toast ẩn. Toast không nhận focus bàn phím, nên đây chỉ là lối tắt
 * cho chuột: mọi hành động phải còn cách làm khác bằng bàn phím (ví dụ nút giỏ trên thanh trên).
 *
 * @param label  chữ của nút, không rỗng
 * @param action việc cần làm khi bấm
 */
public record ToastAction(String label, Runnable action) {

    public ToastAction {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
        label = label.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
