package com.minishop.order;

import java.math.BigDecimal;
import java.time.Instant;

/** Một dòng đơn trong danh sách và dashboard của admin. {@code statusKey} là lowercase để gắn class CSS. */
public record AdminOrderRow(long id, Instant createdAt, String customerName, String customerEmail,
                            String receiverName, BigDecimal totalAmount, String statusLabel, String statusKey) {
}
