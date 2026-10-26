package com.minishop.user;

import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellMenuEntry;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Nút tài khoản trên thanh trên, theo phiên (QĐ9, QĐ10 của D2). Khách thấy "Đăng nhập" (mở hộp thoại); người đã
 * đăng nhập thấy họ tên và một menu: các mục tính năng khác đã gắn ({@link AccountMenu}) mà vai trò được thấy,
 * rồi "Đăng xuất". Thanh trên được vẽ lại mỗi khi phiên đổi.
 * <p>
 * Mọi phương thức chạy trên luồng giao diện: {@link #contribute} do cửa sổ chính gọi, {@link #sessionChanged} do
 * {@link AppSession} gọi đồng bộ trên luồng đã đổi phiên, {@link #setAction} do tính năng khác gọi trong
 * {@code contribute} của chúng.
 */
@Component
class AccountPresenter implements ShellContributor, SessionListener, AccountMenu {

    static final String LOGOUT_ID = "accountMenuLogout";
    static final String LOGOUT_LABEL = "Đăng xuất";
    static final String LOGGED_OUT_TOAST = "Bạn đã đăng xuất.";
    static final String GREETING_PREFIX = "Xin chào, ";

    private final AppSession session;
    private final LoginPrompt loginPrompt;
    private final Navigator navigator;
    private final Toasts toasts;
    private final Map<AccountMenuItem, Runnable> actions = new EnumMap<>(AccountMenuItem.class);
    /** {@code null} cho tới khi cửa sổ chính gọi {@link #contribute}. */
    private ShellPresenter shell;

    AccountPresenter(AppSession session, LoginPrompt loginPrompt, Navigator navigator, Toasts toasts) {
        this.session = session;
        this.loginPrompt = loginPrompt;
        this.navigator = navigator;
        this.toasts = toasts;
    }

    @Override
    public void contribute(ShellPresenter shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        render();
    }

    @Override
    public void sessionChanged(CurrentUser previous, CurrentUser current) {
        render();
    }

    @Override
    public void setAction(AccountMenuItem item, Runnable action) {
        Objects.requireNonNull(item, "item");
        if (action == null) {
            actions.remove(item);
        } else {
            actions.put(item, action);
        }
        render();
    }

    /** Đăng xuất (AC-009-10): đổi phiên, về trang chủ, báo bằng toast. Không hỏi xác nhận vì không mất dữ liệu. */
    void logout() {
        try {
            session.signOut();
        } finally {
            navigator.goHome();
            toasts.show(LOGGED_OUT_TOAST, ToastKind.INFO);
        }
    }

    /** Chưa có thanh trên (chưa {@code contribute}) thì chưa có gì để vẽ. */
    private void render() {
        if (shell == null) {
            return;
        }
        CurrentUser user = session.currentUser().orElse(null);
        if (user == null) {
            shell.setAccountMenu(List.of());
            shell.setAccountLabel(null);
            shell.setAccountAccessibleText(null);
            shell.setAction(ShellAction.ACCOUNT, loginPrompt::open);
            return;
        }
        String name = displayName(user);
        shell.setAccountLabel(name);
        shell.setAccountAccessibleText(GREETING_PREFIX + name);
        shell.setAccountMenu(menuFor(user));
        shell.setAction(ShellAction.ACCOUNT, null);
    }

    private List<ShellMenuEntry> menuFor(CurrentUser user) {
        List<ShellMenuEntry> entries = new ArrayList<>();
        for (AccountMenuItem item : AccountMenuItem.values()) {
            Runnable action = actions.get(item);
            if (action != null && item.visibleTo(user.role())) {
                entries.add(new ShellMenuEntry(item.menuId(), item.label(), action));
            }
        }
        entries.add(new ShellMenuEntry(LOGOUT_ID, LOGOUT_LABEL, this::logout));
        return entries;
    }

    /** Họ tên; hiếm khi trống (đăng ký bắt buộc), khi đó dùng email để nút không quay về "Đăng nhập". */
    private static String displayName(CurrentUser user) {
        String name = user.fullName();
        return name == null || name.isBlank() ? user.email() : name.strip();
    }
}
