package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Form "Thêm danh mục" / "Sửa danh mục" (AC-006-11 đến -14, QĐ3, QĐ4 của D5). Form là một màn trong khu quản trị,
 * không phải hộp thoại; "Hủy" và lưu xong đều về {@link AdminCategoriesRoute}.
 *
 * @param categoryId id danh mục cần sửa; {@code null} nghĩa là thêm mới
 */
record AdminCategoryFormRoute(Long categoryId) implements Route {

    /** Form thêm mới. */
    static AdminCategoryFormRoute newCategory() {
        return new AdminCategoryFormRoute(null);
    }

    /** Form sửa danh mục {@code id}. */
    static AdminCategoryFormRoute edit(long id) {
        return new AdminCategoryFormRoute(id);
    }

    /** {@code true} khi đây là form thêm mới. */
    boolean isNew() {
        return categoryId == null;
    }
}
