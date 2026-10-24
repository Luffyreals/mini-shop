package com.minishop.order;

/**
 * Một nút đổi trạng thái trên chi tiết đơn. {@code targetStatus} là tên enum gửi lên khi bấm;
 * {@code confirmMessage} null nghĩa là không hỏi xác nhận.
 */
public record StatusAction(String targetStatus, String label, String confirmMessage, boolean danger) {
}
