package com.minishop.catalog;

import java.util.List;

record ProductListView(List<ProductCardView> products, long totalElements, int totalPages,
                       ProductFilter filter, List<CategoryOption> categories) {
}
