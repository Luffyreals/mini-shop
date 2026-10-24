package com.minishop.catalog;

/** Một dòng trong danh sách danh mục của admin; {@code productCount} đếm cả sản phẩm đã ẩn. */
public record CategoryRow(long id, String name, String description, long productCount) {
}
