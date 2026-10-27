/**
 * Giỏ hàng: giỏ trong bộ nhớ ({@code SessionCart} sau {@code GuestCartStore}, bản duy nhất là
 * {@code InMemoryGuestCartStore}) cho khách, giỏ DB cho người đã đăng nhập. Chủ giỏ hiện tại chỉ lấy từ
 * {@code CurrentCartOwner} (đọc phiên đăng nhập). Việc gộp giỏ khách vào giỏ DB khi đăng nhập là
 * {@code GuestCartLifecycle} (một {@code LoginHook} của {@code user}) gọi {@code CartService.mergeGuestCart}; cùng
 * lớp đó xóa giỏ khách khi đăng xuất (ADR-0006, ADR-0014). Mọi thao tác ghi giỏ DB khóa dòng {@code users} của
 * chủ giỏ trước tiên (ADR-0008).
 * <p>API công khai hẹp cho {@code order}: {@code CartCheckout}, {@code CheckoutLine} (đọc giỏ DB sau khi khóa,
 * xóa giỏ), {@code CheckoutEntry} (cổng mở màn Thanh toán) và {@code CartBadge} (làm mới số trên nút giỏ sau
 * khi đặt hàng). Phần còn lại package-private.
 * <p>Drawer giỏ hàng (D4): {@code CartDrawer} (giao diện), {@code CartDrawerPresenter} (trạng thái, một cái cho
 * mỗi lần mở) và {@code CartDrawer.Opener} gắn nút giỏ trên thanh trên. Nút "Đặt hàng" của drawer gọi
 * {@code CheckoutEntry}; màn Thanh toán thuộc {@code order}, và {@code CartBadgePresenter} cài đặt
 * {@code CartBadge}.
 * <p>{@code AddToCartPresenter} cài đặt {@code catalog.AddToCart} ("Thêm vào giỏ" của màn hình cửa hàng): {@code catalog}
 * định nghĩa giao diện, {@code cart} cài đặt, cùng kiểu đảo phụ thuộc với {@code LoginHook}, nên phụ thuộc vẫn là
 * {@code cart -> catalog}. {@code CheckoutEntry} đảo phụ thuộc theo cùng cách: {@code cart} định nghĩa,
 * {@code order} cài đặt, nên {@code cart} không bao giờ import {@code order}.
 * <p>Được phép dùng: {@code catalog}, {@code user}, {@code common}, và {@code desktop} (cho presenter của giỏ).
 */
package com.minishop.cart;
