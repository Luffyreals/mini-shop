package com.minishop.order;

import java.math.BigDecimal;

record OrderLineView(String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
}
