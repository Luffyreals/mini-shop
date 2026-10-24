package com.minishop.catalog;

import java.math.BigDecimal;

/** Dữ liệu của trang sửa sản phẩm. {@code imageUrl} null khi chưa có ảnh. */
public record ProductEditView(long id, String name, long categoryId, BigDecimal price, String description,
                              int stock, boolean active, String imageUrl) {
}
