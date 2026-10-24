package com.minishop.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record OrderDetailView(long id, Instant createdAt, String statusLabel, String statusKey, String paymentMethodLabel,
                       String receiverName, String phone, String shippingAddress, String note,
                       List<OrderLineView> lines, BigDecimal totalAmount, boolean cancellable) {
}
