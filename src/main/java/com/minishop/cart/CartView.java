package com.minishop.cart;

import java.math.BigDecimal;
import java.util.List;

/** Giỏ hàng để hiển thị. {@code total} chỉ cộng các dòng mua được. */
record CartView(List<CartLineView> lines, BigDecimal total) {

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
