package com.minishop.cart;

import java.math.BigDecimal;

/**
 * Một dòng giỏ để hiển thị.
 *
 * @param lineTotal      {@code null} khi dòng không mua được (hết hàng, ngừng bán)
 * @param availableStock chỉ có nghĩa khi {@code LOW_STOCK}, các trạng thái khác là 0
 */
record CartLineView(Long productId, String name, String imageUrl, BigDecimal unitPrice, int quantity,
                    BigDecimal lineTotal, CartLineStatus status, int availableStock) {

    public boolean isPurchasable() {
        return status == CartLineStatus.AVAILABLE || status == CartLineStatus.LOW_STOCK;
    }

    public boolean isLowStock() {
        return status == CartLineStatus.LOW_STOCK;
    }

    public boolean isOutOfStock() {
        return status == CartLineStatus.OUT_OF_STOCK;
    }

    public boolean isDiscontinued() {
        return status == CartLineStatus.DISCONTINUED;
    }
}
