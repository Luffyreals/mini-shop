package com.minishop.order;

import java.math.BigDecimal;

/** Một dòng hàng của đơn (tên và giá đã chụp lúc mua). Public để chi tiết đơn của admin dùng lại. */
public record OrderLineView(String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
}
