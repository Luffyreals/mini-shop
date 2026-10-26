package com.minishop.desktop;

import java.util.Objects;

/**
 * Một mục trong menu của nút tài khoản (QĐ9 của D2): {@link ShellPresenter#accountMenuEntries()}.
 * <p>
 * Một mục luôn có hành động thật, nên không có mục nào bấm mà không xảy ra gì: tính năng chỉ thêm mục khi đã
 * có việc để làm.
 *
 * @param id     id của node menu, cố định để test tra được (ví dụ {@code accountMenuLogout})
 * @param label  chữ hiện trên mục, không rỗng
 * @param action việc chạy trên luồng giao diện khi người dùng chọn mục
 */
public record ShellMenuEntry(String id, String label, Runnable action) {

    public ShellMenuEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
