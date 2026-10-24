/**
 * Giỏ hàng: giỏ session ({@code SessionCart}) cho khách, giỏ DB cho người đã đăng nhập, gộp khi đăng nhập
 * bằng listener nằm trong package này (ADR-0006). Mọi thao tác ghi giỏ DB khóa dòng {@code users} của
 * chủ giỏ trước tiên (ADR-0008).
 * <p>API công khai hẹp cho {@code order}: {@code CartCheckout}, {@code CheckoutLine} (đọc giỏ DB sau khi khóa,
 * xóa giỏ). Phần còn lại package-private.
 * <p>Được phép dùng: {@code catalog}, {@code user}, {@code common}.
 */
package com.minishop.cart;
