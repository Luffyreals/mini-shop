package com.minishop.catalog;

import java.util.List;

/**
 * Everything the home page shows (QĐ5, D3). {@code featured} is the newest active product, {@code null} when
 * there is none. The lists are never {@code null} and are immutable copies.
 */
record HomeView(ProductDetailView featured, List<ProductCardView> moreNew, List<LowStockCardView> lowStock,
                List<CategoryOption> categories) {

    HomeView {
        moreNew = List.copyOf(moreNew);
        lowStock = List.copyOf(lowStock);
        categories = List.copyOf(categories);
    }
}
