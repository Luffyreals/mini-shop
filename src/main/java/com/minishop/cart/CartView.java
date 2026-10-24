package com.minishop.cart;

import java.math.BigDecimal;
import java.util.List;

/** Giỏ hàng để hiển thị. {@code total} chỉ cộng các dòng mua được. */
record CartView(List<CartLineView> lines, BigDecimal total) {

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Nút "Đặt hàng" chỉ bật khi giỏ không trống và mọi dòng đặt được (AC-005-1). */
    public boolean isOrderable() {
        return !lines.isEmpty() && lines.stream().allMatch(l -> l.status() == CartLineStatus.AVAILABLE);
    }
}
