package com.minishop.order;

/** Một lựa chọn của bộ lọc trạng thái; {@code value} null nghĩa là "Tất cả". */
public record StatusFilterOption(String value, String label, boolean selected) {
}
