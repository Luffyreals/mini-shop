package com.minishop.catalog;

/** Một sản phẩm đang bán sắp hết hàng (dashboard). */
public record LowStockRow(long id, String name, int stock) {
}
