package com.minishop.cart;

import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.user.CurrentUser;
import com.minishop.user.SessionListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Số trên biểu tượng giỏ ở thanh trên (QĐ11 của D2): tổng số lượng trong giỏ của chủ giỏ hiện tại, đọc qua
 * {@link CartService#totalQuantity} ngoài luồng giao diện.
 *
 * <p><b>Khi nào làm mới:</b> khi thanh trên vừa dựng ({@link #contribute}), mỗi khi phiên đổi
 * ({@link #sessionChanged}) và khi màn hình gọi {@link #refresh()} sau một lần ghi giỏ (D3, D4).
 *
 * <p><b>Thứ tự:</b> chạy <b>sau</b> {@link GuestCartLifecycle} ({@code HIGHEST_PRECEDENCE}), nên sau đăng xuất
 * giỏ khách đã rỗng. Sau đăng nhập, việc gộp giỏ đã commit từ trước khi phiên đổi, nên số đếm là của giỏ đã gộp.
 *
 * <p><b>Chống kết quả cũ đè kết quả mới</b> (ví dụ đăng nhập rồi đăng xuất liên tiếp): mỗi {@link #refresh()}
 * (1) hủy việc trước, nên callback của nó không bao giờ chạy, và (2) tăng một số thế hệ; {@code onSuccess} chỉ ghi
 * khi số thế hệ của nó vẫn là số mới nhất. Lớp (2) bù cho khe hở rất nhỏ giữa lúc kiểm tra cờ hủy và lúc callback
 * thật sự chạy trên luồng giao diện. Chủ giỏ được đọc <b>trên luồng giao diện, trước khi gửi việc</b>, để việc nền
 * không đọc phiên lần nữa và thấy một người khác.
 *
 * <p>Mọi phương thức chạy trên luồng giao diện (nên các trường không cần đồng bộ hóa).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CartBadgePresenter implements SessionListener, ShellContributor, CartBadge {

    private final CartService cartService;
    private final CurrentCartOwner currentOwner;
    private final BackgroundTasks tasks;
    private final UnexpectedErrors errors;

    /** {@code null} cho tới khi cửa sổ chính gọi {@link #contribute}. */
    private ShellPresenter shell;
    private BackgroundTasks.Handle pending;
    private long generation;

    CartBadgePresenter(CartService cartService, CurrentCartOwner currentOwner, BackgroundTasks tasks,
                       UnexpectedErrors errors) {
        this.cartService = cartService;
        this.currentOwner = currentOwner;
        this.tasks = tasks;
        this.errors = errors;
    }

    @Override
    public void contribute(ShellPresenter shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        refresh();
    }

    @Override
    public void sessionChanged(CurrentUser previous, CurrentUser current) {
        refresh();
    }

    /**
     * Đọc lại tổng số lượng của giỏ hiện tại và đặt vào thanh trên. D3 và D4 gọi sau mỗi lần ghi giỏ. Chưa có
     * thanh trên thì chưa có gì để cập nhật. Là phương thức của {@link CartBadge}, để {@code order} làm mới số sau
     * khi đặt hàng mà không biết lớp này (QĐ4 của D4).
     */
    @Override
    public void refresh() {
        if (shell == null) {
            return;
        }
        if (pending != null) {
            pending.cancel();
        }
        CartOwner owner = currentOwner.get();
        long mine = ++generation;
        pending = tasks.submit(
                () -> cartService.totalQuantity(owner),
                count -> {
                    if (mine == generation) {
                        shell.setCartCount(count);
                    }
                },
                errors::report);
    }
}
