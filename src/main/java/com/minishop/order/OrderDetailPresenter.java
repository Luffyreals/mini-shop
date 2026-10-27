package com.minishop.order;

import com.minishop.common.NotFoundException;
import com.minishop.common.VnDateTimeFormatter;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ConfirmDialog;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn chi tiết đơn hàng và việc khách tự hủy đơn (AC-005-23, -24, -27, -28, QĐ2, QĐ9,
 * QĐ11 của D4). Mỗi lần điều hướng tới một {@link OrderDetailRoute} có một presenter mới ({@code prototype}); màn
 * gọi {@link #load} một lần và {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@code OrderService.getOrder} và {@code cancelByCustomer} chạy qua {@link BackgroundTasks};
 * property chỉ đổi trên luồng giao diện. Người dùng được đọc từ {@link AppSession} trên luồng giao diện trước khi
 * gửi việc, chỉ {@code userId} đi vào service. Đơn không có hoặc của người khác ({@link NotFoundException}, lúc nạp
 * hay lúc hủy) thì báo "Không tìm thấy đơn hàng." rồi về {@link OrderDetailRoute#back()} (AC-009-13).
 *
 * <p><b>Hủy đơn:</b> {@link #requestCancel} chỉ mở hộp xác nhận; service chưa được gọi tới khi người dùng xác nhận
 * ({@link #cancel}). {@link #busyProperty()} giữ {@code true} từ lúc gửi tới khi đơn được nạp lại, nên bấm đúp
 * không gửi hai lần và nút không hiện trạng thái cũ giữa chừng.
 *
 * <p>Lỗi lạ đi vào {@link UnexpectedErrors} và mở khóa. Sau {@link #dispose} không callback nào đổi property hay
 * điều hướng nữa; riêng toast "Đã hủy đơn hàng #{id}." vẫn hiện, vì đơn đã thật sự bị hủy. Presenter chỉ import
 * {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class OrderDetailPresenter {

    static final String MSG_NOT_FOUND = "Không tìm thấy đơn hàng.";
    static final String MSG_CANCELLED = "Đã hủy đơn hàng #%d.";
    static final String MSG_NOT_CANCELLABLE = "Đơn hàng này không thể hủy nữa.";
    static final String CONFIRM_MESSAGE = "Bạn có chắc muốn hủy đơn hàng này?";
    static final String CONFIRM_LABEL = "Hủy đơn hàng";
    static final String DISMISS_LABEL = "Giữ đơn hàng";

    private static final VndFormatter MONEY = new VndFormatter();
    private static final VnDateTimeFormatter DATES = new VnDateTimeFormatter();

    private final OrderService orderService;
    private final AppSession session;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final Toasts toasts;
    private final ConfirmDialog confirmDialog;
    private final UnexpectedErrors errors;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final ObjectProperty<OrderDetailView> order = new SimpleObjectProperty<>();
    private final StringProperty titleText = new SimpleStringProperty("");
    private final StringProperty dateText = new SimpleStringProperty("");
    private final StringProperty totalText = new SimpleStringProperty("");
    private final StringProperty bannerText = new SimpleStringProperty("");
    private final BooleanProperty cancellable = new SimpleBooleanProperty(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty loadFailed = new SimpleBooleanProperty(false);
    private final ObservableList<LineRow> lines = FXCollections.observableArrayList();

    private OrderDetailRoute route;
    private boolean showBanner;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    OrderDetailPresenter(OrderService orderService, AppSession session, BackgroundTasks tasks, Navigator navigator,
                         Toasts toasts, ConfirmDialog confirmDialog, UnexpectedErrors errors) {
        this.orderService = orderService;
        this.session = session;
        this.tasks = tasks;
        this.navigator = navigator;
        this.toasts = toasts;
        this.confirmDialog = confirmDialog;
        this.errors = errors;
    }

    /**
     * Một dòng hàng của đơn, chữ đã định dạng sẵn.
     *
     * @param productName   tên sản phẩm lúc mua
     * @param unitPriceText đơn giá lúc mua
     * @param quantityText  số lượng
     * @param lineTotalText thành tiền
     */
    record LineRow(String productName, String unitPriceText, String quantityText, String lineTotalText) {
    }

    /** Nhận route của màn và nạp đơn ở nền. */
    void load(OrderDetailRoute newRoute) {
        this.route = Objects.requireNonNull(newRoute, "route");
        this.showBanner = newRoute.justPlaced();
        if (session.currentUser().isEmpty()) {
            // Không xảy ra qua giao diện. Không gọi service; về trang chủ sau khi màn được dựng xong
            pending = tasks.submit(() -> Boolean.TRUE, done -> navigator.goHome());
            return;
        }
        reload();
    }

    private void reload() {
        CurrentUser user = session.currentUser().orElse(null);
        if (user == null) {
            busy.set(false);
            navigator.goHome();
            return;
        }
        if (pending != null) {
            pending.cancel();
        }
        long userId = user.id();
        long orderId = route.orderId();
        loading.set(true);
        pending = tasks.submit(() -> orderService.getOrder(userId, orderId), this::show, this::loadFailed);
    }

    private void show(OrderDetailView view) {
        titleText.set("Đơn hàng #" + view.id());
        dateText.set(DATES.format(view.createdAt()));
        totalText.set(MONEY.format(view.totalAmount()));
        bannerText.set(showBanner ? CheckoutPresenter.MSG_PLACED.formatted(view.id()) : "");
        lines.setAll(view.lines().stream()
                .map(line -> new LineRow(line.productName(), MONEY.format(line.unitPrice()),
                        Integer.toString(line.quantity()), MONEY.format(line.lineTotal())))
                .toList());
        cancellable.set(view.cancellable());
        order.set(view);
        loadFailed.set(false);
        busy.set(false);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        busy.set(false);
        if (failure instanceof NotFoundException) {
            gone();
            return;
        }
        if (order.get() == null) {
            loadFailed.set(true);   // lần nạp đầu lỗi: màn hiện khối lỗi thay vì để trống
        }
        errors.report(failure);
    }

    /**
     * "Thử lại" sau một lỗi lạ ở lần nạp đầu (QĐ12): tắt {@link #loadFailedProperty()} rồi nạp lại đúng route. Bỏ
     * qua khi đã {@link #dispose}, khi đang nạp hoặc khi chưa có lỗi.
     */
    void retry() {
        if (disposed || route == null || loading.get() || !loadFailed.get()) {
            return;
        }
        loadFailed.set(false);
        reload();
    }

    /** Đơn không còn hoặc không phải của người này: báo rồi về danh sách (AC-009-13). */
    private void gone() {
        toasts.show(MSG_NOT_FOUND, ToastKind.ERROR);
        back();
    }

    /** "Hủy đơn hàng": hỏi xác nhận; chưa gọi service. Bỏ qua khi đơn không hủy được hoặc đang gửi. */
    void requestCancel() {
        if (disposed || route == null || busy.get() || !cancellable.get()) {
            return;
        }
        confirmDialog.ask(CONFIRM_MESSAGE, CONFIRM_LABEL, DISMISS_LABEL, this::cancel);
    }

    /** Gửi lệnh hủy sau khi người dùng đã xác nhận. Gọi lần hai khi đang gửi không làm gì. */
    void cancel() {
        if (disposed || route == null || busy.get() || !cancellable.get()) {
            return;
        }
        CurrentUser user = session.currentUser().orElse(null);
        if (user == null) {
            navigator.goHome();
            return;
        }
        long userId = user.id();
        long orderId = route.orderId();
        busy.set(true);
        // Không lưu vào pending: dispose không được hủy callback này, để người dùng luôn biết kết quả
        tasks.submit(() -> orderService.cancelByCustomer(userId, orderId), this::cancelled, this::cancelFailed);
    }

    private void cancelled(CancelResult result) {
        // Biểu thức switch: thêm một giá trị vào CancelResult mà quên xử lý thì không biên dịch được
        boolean cancelled = switch (result) {
            case CANCELLED -> true;
            case NOT_CANCELLABLE -> false;
        };
        if (cancelled) {
            toasts.show(MSG_CANCELLED.formatted(route.orderId()), ToastKind.SUCCESS);
        } else {
            toasts.show(MSG_NOT_CANCELLABLE, ToastKind.ERROR);
        }
        if (disposed) {
            return;
        }
        // Nạp lại tại chỗ; giữ busy tới khi có dữ liệu mới. Từ đây đơn không còn là "vừa đặt"
        showBanner = false;
        reload();
    }

    private void cancelFailed(Throwable failure) {
        if (disposed) {
            if (!(failure instanceof NotFoundException)) {
                errors.report(failure);
            }
            return;
        }
        busy.set(false);
        if (failure instanceof NotFoundException) {
            gone();
            return;
        }
        errors.report(failure);
    }

    /** "Quay lại danh sách đơn": về trang danh sách đã dẫn tới màn này (trang 1 sau khi vừa đặt hàng). */
    void back() {
        if (disposed || route == null) {
            return;
        }
        navigator.navigateTo(route.back());
    }

    /** Hủy việc nạp đang chờ: sau đó không callback nào của màn này đổi gì nữa. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
        }
    }

    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} khi lần nạp đầu của route này lỗi vì một lỗi lạ (màn hiện khối "Thử lại"). */
    ReadOnlyBooleanProperty loadFailedProperty() {
        return loadFailed;
    }

    /** Đơn đang hiện; {@code null} tới lần nạp thành công đầu tiên. */
    ReadOnlyObjectProperty<OrderDetailView> orderProperty() {
        return order;
    }

    /** "Đơn hàng #{id}". */
    ReadOnlyStringProperty titleTextProperty() {
        return titleText;
    }

    /** Ngày đặt, giờ Việt Nam. */
    ReadOnlyStringProperty dateTextProperty() {
        return dateText;
    }

    /** Tổng cộng, ví dụ {@code 1.250.000 ₫}. */
    ReadOnlyStringProperty totalTextProperty() {
        return totalText;
    }

    /** "Đặt hàng thành công. Mã đơn của bạn là #{id}." khi vừa đặt xong; rỗng ở mọi trường hợp khác. */
    ReadOnlyStringProperty bannerTextProperty() {
        return bannerText;
    }

    /** Có hiện nút "Hủy đơn hàng" không (đơn {@code PENDING}). */
    ReadOnlyBooleanProperty cancellableProperty() {
        return cancellable;
    }

    /** {@code true} từ lúc gửi lệnh hủy tới khi đơn được nạp lại; khóa nút hủy. */
    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    /** Các dòng hàng đã định dạng, theo thứ tự của đơn. */
    ObservableList<LineRow> lines() {
        return lines;
    }
}
