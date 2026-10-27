package com.minishop.order;

/**
 * Dữ liệu để dựng màn Thanh toán trong một lần gọi (QĐ10 của D4).
 *
 * @param checkout giỏ ở giá hiện tại: dòng hàng, tổng, và có đặt được không
 * @param prefill  form giao hàng điền sẵn (họ tên, số điện thoại nếu có; địa chỉ và ghi chú rỗng)
 */
record CheckoutPage(CheckoutView checkout, CheckoutForm prefill) {
}
