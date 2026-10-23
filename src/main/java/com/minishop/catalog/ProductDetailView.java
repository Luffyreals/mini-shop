package com.minishop.catalog;

import java.math.BigDecimal;

record ProductDetailView(Long id, String name, String categoryName, BigDecimal price, String description,
                         boolean inStock, String imageUrl) {
}
