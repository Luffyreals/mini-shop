package com.minishop.order;

import java.math.BigDecimal;

record CheckoutLineView(String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
}
