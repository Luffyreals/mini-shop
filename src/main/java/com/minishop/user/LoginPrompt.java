package com.minishop.user;

/**
 * Cửa để tính năng khác mở hộp thoại đăng nhập (AC-009-4, QĐ8 của D2). Java thuần: không import JavaFX.
 * <p>
 * Dùng cho nút cần đăng nhập, ví dụ "Đặt hàng" hay "Đơn hàng của tôi" ở D4:
 * <pre>{@code loginPrompt.requireLogin(() -> navigator.navigateTo(new CheckoutRoute()));}</pre>
 * Mọi phương thức gọi trên luồng giao diện.
 */
public interface LoginPrompt {

    /** Mở hộp thoại ở chế độ đăng nhập. Không làm gì khi hộp thoại đang mở hoặc đã có người đăng nhập. */
    void open();

    /**
     * Nếu đã đăng nhập thì chạy {@code onLoggedIn} ngay. Nếu chưa thì nhớ việc đó (thay việc nhớ trước nếu có)
     * và mở hộp thoại; đăng nhập xong thì việc được chạy <b>đúng một lần</b>, sau khi phiên đã đổi và hộp thoại
     * đã đóng. Việc nhớ được giữ khi người dùng chuyển qua lại giữa đăng nhập và đăng ký, và bị bỏ khi họ đóng
     * hộp thoại mà chưa đăng nhập.
     */
    void requireLogin(Runnable onLoggedIn);
}
