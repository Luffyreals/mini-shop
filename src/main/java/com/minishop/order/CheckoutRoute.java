package com.minishop.order;

import com.minishop.desktop.Route;

/**
 * Màn Thanh toán (QĐ2 của D4). Chỉ vào được qua {@code cart.CheckoutEntry}, luôn sau khi đã đăng nhập.
 */
record CheckoutRoute() implements Route {
}
