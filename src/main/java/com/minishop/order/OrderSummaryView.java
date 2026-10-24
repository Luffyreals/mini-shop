package com.minishop.order;

import java.math.BigDecimal;
import java.time.Instant;

record OrderSummaryView(long id, Instant createdAt, BigDecimal totalAmount, String statusLabel, String statusKey) {
}
