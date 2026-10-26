/**
 * Người dùng, vai trò (enum USER/ADMIN), đăng ký và đăng nhập: chuẩn hóa email, chính sách mật khẩu,
 * {@link com.minishop.user.CurrentUser} (record public cho các tính năng khác biết "ai đang đăng nhập") và phiên
 * làm việc {@link com.minishop.user.AppSession} (Java thuần, đọc được từ mọi luồng, chỉ gói {@code user} đổi được).
 * {@code AuthService} kiểm mật khẩu (một lần {@code matches} cho cả hai nhánh, chặn mật khẩu quá 72 byte, không
 * chạy trong giao dịch). Hai điểm cắm do {@code user} định nghĩa để tính năng khác cài đặt mà {@code user} không biết
 * chúng: {@link com.minishop.user.LoginHook} (việc cần làm khi đăng nhập, ví dụ gộp giỏ) và
 * {@link com.minishop.user.SessionListener} (nghe đăng nhập/đăng xuất). {@code AppSession} cài đặt
 * {@link com.minishop.common.AdminAccess} để service quản trị kiểm vai trò ADMIN. Hộp thoại đăng nhập và menu tài
 * khoản (F2) cũng nằm ở đây. {@link com.minishop.user.DemoAccounts} giữ email của hai tài khoản demo. Được dùng
 * {@code common} và {@code desktop}; không phụ thuộc tính năng nào khác.
 */
package com.minishop.user;
