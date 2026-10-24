/**
 * Danh mục và sản phẩm: xem danh sách, tìm kiếm, lọc, chi tiết; trừ/hoàn tồn kho; quản trị danh mục và sản phẩm.
 * <p>Được phép dùng: {@code storage}, {@code common}.
 * <p>Chỉ {@code order} trừ/cộng kho ({@code decrementStock}, {@code incrementStock}); chỉ
 * {@code ProductAdminService} điều chỉnh kho ({@code adjustStock}). Thêm/sửa sản phẩm và xóa danh mục khóa
 * dòng {@code categories} trước (ADR-0010).
 * <p>API công khai cho {@code admin}: {@code CategoryAdminService}, {@code ProductAdminService} và các
 * record của chúng.
 */
package com.minishop.catalog;
