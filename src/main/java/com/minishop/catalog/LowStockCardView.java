package com.minishop.catalog;

/** One row of the home page "Sắp hết hàng" block: an active product with 1..LOW_STOCK_THRESHOLD units left. */
record LowStockCardView(Long id, String name, int stock, String imageUrl) {
}
