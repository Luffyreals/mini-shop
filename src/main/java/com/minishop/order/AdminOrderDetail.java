package com.minishop.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Chi tiết một đơn cho admin. {@code actions} suy từ {@code OrderStatus.canTransitionTo()} (BR-006-12). */
public record AdminOrderDetail(long id, Instant createdAt, Instant updatedAt, String statusLabel, String statusKey,
                               String paymentMethodLabel, String customerName, String customerEmail,
                               String receiverName, String phone, String shippingAddress, String note,
                               List<OrderLineView> lines, BigDecimal totalAmount, List<StatusAction> actions) {

    /** True khi không còn bước nào (đã giao hoặc đã hủy). Public: template đọc qua SpEL. */
    public boolean isFinalStatus() {
        return actions.isEmpty();
    }
}
