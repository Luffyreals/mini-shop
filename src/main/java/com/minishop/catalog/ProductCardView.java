package com.minishop.catalog;

import java.math.BigDecimal;

record ProductCardView(Long id, String name, String categoryName, BigDecimal price, boolean inStock,
                       String imageUrl) {
}
