package com.minishop.user;

/**
 * Cửa để tính năng khác thêm mục vào menu của nút tài khoản (QĐ9 của D2). Java thuần: không import JavaFX.
 * <p>
 * Một mục chỉ hiện khi đã có hành động thật <b>và</b> vai trò của người đăng nhập cho phép
 * ({@link AccountMenuItem}), nên không có mục bấm mà không làm gì, và người dùng thường không bao giờ thấy
 * "Quản trị" (AC-009-8). Mục "Đăng xuất" thuộc {@code user} nên không nằm ở đây. Gọi trên luồng giao diện, thường
 * trong {@code ShellContributor.contribute}.
 */
public interface AccountMenu {

    /** Gắn hành động cho một mục; {@code null} để gỡ mục khỏi menu. */
    void setAction(AccountMenuItem item, Runnable action);
}
