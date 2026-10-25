/**
 * Người dùng, vai trò (enum USER/ADMIN), đăng ký và đăng nhập: chuẩn hóa email, {@code UserDetailsService}
 * đọc người dùng từ DB, và {@link com.minishop.user.CurrentUser}, kiểu public cho các tính năng khác
 * biết "ai đang đăng nhập". Cũng có giới hạn đăng nhập sai ({@link com.minishop.user.LoginThrottle}, GĐ8) và
 * hai tài khoản demo ({@link com.minishop.user.DemoAccountService}: email và việc đặt lại mật khẩu ở chế độ
 * công khai). Không phụ thuộc tính năng nào khác.
 */
package com.minishop.user;
