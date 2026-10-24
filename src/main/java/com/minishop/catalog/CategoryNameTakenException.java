package com.minishop.catalog;

/** Tên danh mục đã tồn tại (không phân biệt hoa thường). Unchecked để transaction vẫn rollback. */
public final class CategoryNameTakenException extends RuntimeException {

    public CategoryNameTakenException(String message) {
        super(message);
    }

    public CategoryNameTakenException(String message, Throwable cause) {
        super(message, cause);
    }
}
