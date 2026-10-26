/**
 * Giỏ hàng: giỏ trong bộ nhớ ({@code SessionCart} sau {@code GuestCartStore}, bản duy nhất là
 * {@code InMemoryGuestCartStore}) cho khách, giỏ DB cho người đã đăng nhập. Chủ giỏ hiện tại chỉ lấy từ
 * {@code CurrentCartOwner} (đọc phiên đăng nhập). Việc gộp giỏ khách vào giỏ DB khi đăng nhập là
 * {@code GuestCartLifecycle} (một {@code LoginHook} của {@code user}) gọi {@code CartService.mergeGuestCart}; cùng
 * lớp đó xóa giỏ khách khi đăng xuất (ADR-0006, ADR-0014). Mọi thao tác ghi giỏ DB khóa dòng {@code users} của
 * chủ giỏ trước tiên (ADR-0008).
 * <p>API công khai hẹp cho {@code order}: {@code CartCheckout}, {@code CheckoutLine} (đọc giỏ DB sau khi khóa,
 * xóa giỏ). Phần còn lại package-private.
 * <p>Được phép dùng: {@code catalog}, {@code user}, {@code common}, và {@code desktop} (cho presenter của giỏ).
 */
package com.minishop.cart;
