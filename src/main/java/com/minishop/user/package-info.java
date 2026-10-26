/**
 * Người dùng, vai trò (enum USER/ADMIN), đăng ký: chuẩn hóa email, chính sách mật khẩu, và
 * {@link com.minishop.user.CurrentUser}, record public cho các tính năng khác biết "ai đang đăng nhập".
 * {@link com.minishop.user.DemoAccounts} giữ email của hai tài khoản demo. Việc đăng nhập (kiểm mật khẩu, giới hạn
 * đăng nhập sai) do dịch vụ đăng nhập của D2 đảm nhận. Không phụ thuộc tính năng nào khác.
 */
package com.minishop.user;
