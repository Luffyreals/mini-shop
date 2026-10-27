package com.minishop.cart;

/**
 * Cổng để drawer giỏ hàng mở màn Thanh toán mà {@code cart} không phụ thuộc {@code order} (QĐ3 của D4).
 * {@code cart} định nghĩa giao diện, {@code order} cài đặt: cùng kiểu đảo phụ thuộc với
 * {@code catalog.AddToCart}, nên phụ thuộc vẫn là {@code order -> cart}.
 * <p>Gọi trên luồng giao diện, <b>sau khi drawer đã đóng</b>. Bản cài đặt tự đòi đăng nhập trước (khách thì mở
 * hộp thoại đăng nhập rồi mới vào Thanh toán, AC-009-4), nên người gọi không kiểm tra phiên.
 */
public interface CheckoutEntry {

    /** Mở màn Thanh toán, sau khi người dùng đã đăng nhập. */
    void open();
}
