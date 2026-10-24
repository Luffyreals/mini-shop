package com.minishop.catalog;

/** Danh mục chọn trong form sản phẩm không còn tồn tại. Unchecked để transaction vẫn rollback. */
public final class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(String message) {
        super(message);
    }
}
