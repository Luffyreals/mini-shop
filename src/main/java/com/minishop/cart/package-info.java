/**
 * Giỏ hàng: giỏ session ({@code SessionCart}) cho khách, giỏ DB cho người đã đăng nhập, gộp khi đăng nhập
 * bằng listener nằm trong package này (ADR-0006). Mọi thao tác ghi giỏ DB khóa dòng {@code users} của
 * chủ giỏ trước tiên (ADR-0008).
 * <p>Được phép dùng: {@code catalog}, {@code user}, {@code common}.
 */
package com.minishop.cart;
