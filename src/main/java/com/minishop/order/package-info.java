/**
 * Đặt hàng (COD), lịch sử đơn, luồng trạng thái đơn và hoàn kho khi hủy.
 * <p>Màn hình (D4): Thanh toán ({@code CheckoutRoute}), "Đơn hàng của tôi" ({@code OrdersRoute}) và Chi tiết đơn
 * ({@code OrderDetailRoute}, có hủy đơn {@code PENDING}); mỗi màn là một route package-private cộng
 * {@code *Screen}/{@code *Presenter}. {@code OrdersMenuPresenter} gắn mục "Đơn hàng của tôi" vào menu tài khoản
 * và cài đặt {@code cart.CheckoutEntry}: đòi đăng nhập ({@code LoginPrompt}) rồi mới mở Thanh toán (AC-009-4).
 * <p>Được phép dùng: {@code cart} (chỉ {@code CartCheckout}, {@code CheckoutLine}, {@code CheckoutEntry},
 * {@code CartBadge}), {@code catalog}, {@code user} (cho màn hình: {@code AppSession}, {@code LoginPrompt},
 * {@code AccountMenu}), {@code desktop} (cho màn hình) và {@code common}.
 * <p>API công khai hẹp cho {@code admin}: {@code OrderAdminService} và các record trả về;
 * {@code OrderStatus}, {@code OrderService}, entity và repository vẫn package-private. Admin đổi trạng thái
 * theo cùng quy tắc khóa (ADR-0009).
 * <p>Thứ tự khóa: đặt hàng khóa {@code users} rồi {@code products} tăng dần; đổi trạng thái khóa
 * {@code orders} rồi {@code products} tăng dần (ADR-0009).
 */
package com.minishop.order;
