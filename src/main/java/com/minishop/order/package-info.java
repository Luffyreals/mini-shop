/**
 * Đặt hàng (COD), lịch sử đơn, luồng trạng thái đơn và hoàn kho khi hủy.
 * <p>Được phép dùng: {@code cart} (chỉ {@code CartCheckout}, {@code CheckoutLine}), {@code catalog},
 * {@code user}, {@code common}.
 * <p>Thứ tự khóa: đặt hàng khóa {@code users} rồi {@code products} tăng dần; đổi trạng thái khóa
 * {@code orders} rồi {@code products} tăng dần (ADR-0009).
 */
package com.minishop.order;
