package com.minishop.catalog;

/** Kết quả xóa danh mục: màn hình {@code switch} theo pattern. */
public sealed interface CategoryDeleteResult {

    /** Đã xóa. */
    record Deleted(String name) implements CategoryDeleteResult {
    }

    /** Không xóa vì danh mục còn sản phẩm (kể cả đã ẩn). */
    record InUse(String name, long productCount) implements CategoryDeleteResult {
    }
}
