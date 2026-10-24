package com.minishop.cart;

import java.math.BigDecimal;

/**
 * Một dòng giỏ DB dưới dạng dữ liệu thuần cho {@code order}: không phải entity, nên {@code order} không giữ
 * {@code Cart}, {@code CartItem} hay {@code Product} đang được quản lý.
 */
public record CheckoutLine(long productId, String name, BigDecimal unitPrice, int quantity, boolean active,
                           int stock) {

    /** Đúng BR-005-2: đang bán, còn hàng và số lượng không vượt tồn kho. */
    public boolean orderable() {
        return CartLineStatus.of(active, stock, quantity) == CartLineStatus.AVAILABLE;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
