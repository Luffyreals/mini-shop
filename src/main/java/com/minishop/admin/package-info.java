/**
 * Màn quản trị và form record: chỉ tầng giao diện và gọi service của các tính năng khác. Hiện còn các form
 * ({@code CategoryForm}, {@code NewProductForm}, {@code EditProductForm}), các validator và bộ parse đầu vào
 * ({@code AdminInput}, {@code PriceInput}); các màn hình JavaFX của quản trị được thêm ở D5. Không viết lại
 * nghiệp vụ ở đây: kiểm quyền {@code ADMIN} nằm ở service (ADR-0014).
 * <p>Được phép dùng: {@code catalog}, {@code order}, {@code storage} (chỉ API công khai), {@code user}
 * ({@code CurrentUser}), {@code common}. Không dùng repository, không thấy entity. Không ai phụ thuộc vào
 * {@code admin}.
 */
package com.minishop.admin;
