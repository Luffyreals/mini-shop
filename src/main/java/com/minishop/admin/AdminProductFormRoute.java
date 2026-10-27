package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Form "Thêm sản phẩm" / "Sửa sản phẩm" (AC-006-18 đến -30, QĐ3, QĐ4 của D5). Là một màn trong khu quản trị.
 *
 * @param productId id sản phẩm cần sửa; {@code null} nghĩa là thêm mới
 * @param back      trang danh sách để quay về khi "Hủy", khi không tìm thấy sản phẩm (AC-009-13) và sau khi
 *                  thêm xong (màn sửa của sản phẩm mới mang cùng {@code back}); {@code null} thành
 *                  {@link AdminProductsRoute#first()}
 */
record AdminProductFormRoute(Long productId, AdminProductsRoute back) implements Route {

    AdminProductFormRoute {
        if (back == null) {
            back = AdminProductsRoute.first();
        }
    }

    /** Form thêm mới; "Hủy" về {@code back}. */
    static AdminProductFormRoute newProduct(AdminProductsRoute back) {
        return new AdminProductFormRoute(null, back);
    }

    /** Form sửa sản phẩm {@code id}; "Hủy" về {@code back}. */
    static AdminProductFormRoute edit(long id, AdminProductsRoute back) {
        return new AdminProductFormRoute(id, back);
    }

    /** {@code true} khi đây là form thêm mới. */
    boolean isNew() {
        return productId == null;
    }
}
