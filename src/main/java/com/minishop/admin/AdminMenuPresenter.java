package com.minishop.admin;

import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellPresenter;
import com.minishop.user.AccountMenu;
import com.minishop.user.AccountMenuItem;
import org.springframework.stereotype.Component;

/**
 * Lối vào khu quản trị (QĐ2 của D5, AC-009-8): gắn hành động cho mục "Quản trị" của menu tài khoản. Mục chỉ hiện
 * cho {@code Role.ADMIN} (do {@code user.AccountPresenter} quyết), nên hành động không cần {@code requireLogin};
 * việc chặn thật nằm ở service quản trị (AC-009-9), không phải ở chỗ ẩn mục menu.
 *
 * <p>Không import JavaFX: {@link #contribute} chỉ đặt hành động, chạy trên luồng giao diện do
 * {@code MainWindow} gọi.
 */
@Component
class AdminMenuPresenter implements ShellContributor {

    private final AccountMenu accountMenu;
    private final Navigator navigator;

    AdminMenuPresenter(AccountMenu accountMenu, Navigator navigator) {
        this.accountMenu = accountMenu;
        this.navigator = navigator;
    }

    /** Gắn "Quản trị" vào menu tài khoản: mở {@link AdminDashboardRoute}. */
    @Override
    public void contribute(ShellPresenter shell) {
        accountMenu.setAction(AccountMenuItem.ADMIN, this::openAdmin);
    }

    private void openAdmin() {
        navigator.navigateTo(new AdminDashboardRoute());
    }
}
