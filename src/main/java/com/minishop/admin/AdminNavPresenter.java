package com.minishop.admin;

import com.minishop.desktop.Navigator;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Quyết định của thanh bên khu quản trị (QĐ2 của D5): bấm một mục thì mở route đầu của mục đó. Bấm lại mục đang
 * mở cũng điều hướng (Navigator không bỏ qua route trùng), nên màn được dựng lại ở trang đầu và nạp lại dữ liệu.
 *
 * <p>Là bean đơn, không có trạng thái, dùng chung cho mọi {@link AdminScreen}. Không import JavaFX; chỉ gọi
 * {@link Navigator}, không gọi service.
 */
@Component
class AdminNavPresenter {

    private final Navigator navigator;

    AdminNavPresenter(Navigator navigator) {
        this.navigator = navigator;
    }

    /** Mở route đầu của {@code section} ({@link AdminSection#route()}). */
    void open(AdminSection section) {
        Objects.requireNonNull(section, "section");
        navigator.navigateTo(section.route());
    }
}
