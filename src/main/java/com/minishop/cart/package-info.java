/**
 * Giỏ hàng: giỏ trong bộ nhớ ({@code SessionCart} sau {@code GuestCartStore}) cho khách, giỏ DB cho người đã
 * đăng nhập. Việc gộp giỏ khách vào giỏ DB khi đăng nhập là {@code CartService.mergeGuestCart}, do dịch vụ đăng
 * nhập gọi (ADR-0006, ADR-0014). Mọi thao tác ghi giỏ DB khóa dòng {@code users} của chủ giỏ trước tiên
 * (ADR-0008).
 * <p>API công khai hẹp cho {@code order}: {@code CartCheckout}, {@code CheckoutLine} (đọc giỏ DB sau khi khóa,
 * xóa giỏ). Phần còn lại package-private.
 * <p>Được phép dùng: {@code catalog}, {@code user}, {@code common}.
 */
package com.minishop.cart;
