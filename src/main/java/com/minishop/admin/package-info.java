/**
 * Màn quản trị (D5) và form record: chỉ tầng giao diện, gọi service của các tính năng khác. Khu quản trị có thanh
 * bên (Tổng quan, Đơn hàng, Sản phẩm, Danh mục) và vùng màn bên phải; mỗi màn là một route với presenter riêng.
 * Còn các form ({@code CategoryForm}, {@code NewProductForm}, {@code EditProductForm}), các validator, bộ parse
 * đầu vào ({@code AdminInput}, {@code PriceInput}) và {@code FieldErrors} (mỗi ô một lỗi). Không viết lại nghiệp vụ
 * ở đây: kiểm quyền {@code ADMIN} nằm ở service, qua {@code common.AdminAccess} (AC-009-9); ẩn nút không phải là
 * kiểm quyền.
 * <p>Được phép dùng: {@code catalog}, {@code order}, {@code storage} (chỉ API công khai), {@code user}
 * ({@code AccountMenu}, {@code AccountMenuItem}), {@code desktop}, {@code common}. Không dùng repository, không thấy
 * entity. Không ai phụ thuộc vào {@code admin}.
 */
package com.minishop.admin;
