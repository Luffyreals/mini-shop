package com.minishop.catalog;

/**
 * Cổng để làm mới ngay menu "Danh mục" của thanh trên sau khi admin thêm, sửa hoặc xóa danh mục (QĐ11 của D5).
 * {@code catalog} định nghĩa, {@code StorefrontPresenter} cài đặt, {@code admin} gọi, nên {@code StorefrontPresenter}
 * vẫn là package-private.
 */
public interface CategoriesMenu {

    /** Nạp lại danh sách danh mục ở nền rồi cập nhật menu. Gọi trên luồng giao diện, sau khi thay đổi đã lưu. */
    void refresh();
}
