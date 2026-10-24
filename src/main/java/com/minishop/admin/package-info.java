/**
 * Khu quản trị {@code /admin/**}: chỉ tầng web (controller, form, parse đầu vào) và gọi service của các
 * tính năng khác. Không viết lại nghiệp vụ ở đây.
 * <p>Được phép dùng: {@code catalog}, {@code order}, {@code storage} (chỉ API công khai), {@code user}
 * ({@code CurrentUser}), {@code common}. Không dùng repository, không thấy entity. Không ai phụ thuộc vào
 * {@code admin}.
 */
package com.minishop.admin;
