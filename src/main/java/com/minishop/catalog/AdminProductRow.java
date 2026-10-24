package com.minishop.catalog;

import java.math.BigDecimal;

/** Một dòng của danh sách sản phẩm quản trị (cả sản phẩm đã ẩn). {@code imageUrl} null khi chưa có ảnh. */
public record AdminProductRow(long id, String name, String categoryName, BigDecimal price, int stock,
                              boolean active, String imageUrl) {
}
