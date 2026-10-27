/**
 * Danh mục và sản phẩm: xem danh sách, tìm kiếm, lọc, chi tiết; trừ/hoàn tồn kho; quản trị danh mục và sản phẩm.
 * <p>Được phép dùng: {@code storage}, {@code common}, {@code desktop} (các màn hình của cửa hàng). Không được
 * dùng {@code cart}: {@code AddToCart} là điểm cắm, {@code catalog} định nghĩa và {@code cart} cài đặt.
 * <p>Từ D3 các route ({@code CatalogRoute}, {@code ProductDetailRoute}) là public để D4 (giỏ hàng) điều hướng tới.
 * <p>Chỉ {@code order} trừ/cộng kho ({@code decrementStock}, {@code incrementStock}); chỉ
 * {@code ProductAdminService} điều chỉnh kho ({@code adjustStock}). Thêm/sửa sản phẩm và xóa danh mục khóa
 * dòng {@code categories} trước (ADR-0010).
 * <p>API công khai cho {@code admin}: {@code CategoryAdminService}, {@code ProductAdminService} và các
 * record của chúng; từ D5 mọi phương thức public của hai service này gọi {@code common.AdminAccess.requireAdmin()}
 * trước tiên và không còn nhận {@code adminId}. {@code CategoriesMenu} là cổng để {@code admin} làm mới menu
 * "Danh mục" của thanh trên ({@code StorefrontPresenter} cài đặt, vẫn package-private).
 */
package com.minishop.catalog;
