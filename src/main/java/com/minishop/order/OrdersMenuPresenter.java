package com.minishop.order;

import com.minishop.cart.CheckoutEntry;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellPresenter;
import com.minishop.user.AccountMenu;
import com.minishop.user.AccountMenuItem;
import com.minishop.user.LoginPrompt;
import org.springframework.stereotype.Component;

/**
 * Lối vào các màn của {@code order} (QĐ3, QĐ7 của D4): mục "Đơn hàng của tôi" của menu tài khoản và cổng
 * {@link CheckoutEntry} mà drawer giỏ hàng gọi khi bấm "Đặt hàng". Quy tắc "đặt hàng và xem đơn phải đăng nhập"
 * (BR-005-1, AC-009-4) nằm ở đây, một chỗ: cả hai lối đều đi qua {@link LoginPrompt#requireLogin}, nên khách được
 * mời đăng nhập rồi đi tiếp, người đã đăng nhập đi thẳng.
 * <p>
 * Mục "Đơn hàng của tôi" chỉ hiện trong menu khi đã đăng nhập (AC-005-32, do {@code user.AccountMenu} quyết); đi
 * qua {@code requireLogin} vẫn đúng nếu sau này có lối vào khác cho khách.
 * <p>
 * Là bean đơn (singleton) duy nhất cài đặt {@link CheckoutEntry}. Không import JavaFX: {@link #contribute} chỉ
 * đặt hành động, chạy trên luồng giao diện do {@code MainWindow} gọi.
 */
@Component
class OrdersMenuPresenter implements ShellContributor, CheckoutEntry {

    private final AccountMenu accountMenu;
    private final LoginPrompt loginPrompt;
    private final Navigator navigator;

    OrdersMenuPresenter(AccountMenu accountMenu, LoginPrompt loginPrompt, Navigator navigator) {
        this.accountMenu = accountMenu;
        this.loginPrompt = loginPrompt;
        this.navigator = navigator;
    }

    /** Gắn "Đơn hàng của tôi" vào menu tài khoản. */
    @Override
    public void contribute(ShellPresenter shell) {
        accountMenu.setAction(AccountMenuItem.ORDERS, this::openOrders);
    }

    /**
     * Mở màn Thanh toán sau khi đã đăng nhập (khách thì đăng nhập trước). Gọi sau khi drawer đã đóng. Đang ở màn
     * Thanh toán thì không điều hướng: {@code Navigator} không bỏ qua route trùng, nên điều hướng sẽ dựng màn mới và
     * mất chữ đã nhập (QĐ8); màn hiện tại tự nạp lại tóm tắt khi drawer đóng.
     */
    @Override
    public void open() {
        loginPrompt.requireLogin(() -> {
            if (!(navigator.currentRoute() instanceof CheckoutRoute)) {
                navigator.navigateTo(new CheckoutRoute());
            }
        });
    }

    private void openOrders() {
        loginPrompt.requireLogin(() -> navigator.navigateTo(OrdersRoute.first()));
    }
}
