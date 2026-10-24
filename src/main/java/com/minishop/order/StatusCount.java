package com.minishop.order;

/** Số đơn của một trạng thái (dashboard); {@code status} là tên enum. */
public record StatusCount(String status, String label, long count) {
}
