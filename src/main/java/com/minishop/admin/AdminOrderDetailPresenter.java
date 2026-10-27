package com.minishop.admin;

import com.minishop.common.NotFoundException;
import com.minishop.common.VnDateTimeFormatter;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.ConfirmDialog;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.order.AdminOrderDetail;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderLineView;
import com.minishop.order.StatusAction;
import com.minishop.order.StatusChangeResult;
import java.util.List;
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
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn chi tiết đơn của admin và việc đổi trạng thái (AC-006-40 đến -45, QĐ5, QĐ9,
 * QĐ10 của D5). Mỗi lần điều hướng tới một {@link AdminOrderDetailRoute} có một presenter mới ({@code prototype});
 * màn gọi {@link #load} một lần và {@link #dispose} khi bị thay.
 *
 * <p><b>Dữ liệu:</b> {@code OrderAdminService.getOrder} chạy qua {@link BackgroundTasks}; mỗi lần nạp thành công
 * presenter đổi {@link #detailProperty()} một lần với một {@link Detail} chứa chữ đã định dạng (ngày giờ qua
 * {@code VnDateTimeFormatter}, tiền qua {@code VndFormatter}) và danh sách nút lấy nguyên từ
 * {@code AdminOrderDetail.actions()}: presenter và màn không tự quyết bước nào được phép (BR-006-12). Đơn không còn
 * ({@link NotFoundException}, lúc nạp hay lúc đổi) thì toast "Không tìm thấy đơn hàng." rồi về
 * {@link AdminOrderDetailRoute#back()} (AC-009-13).
 *
 * <p><b>Đổi trạng thái:</b> {@link #runAction} thấy {@code confirmMessage} thì chỉ mở {@link ConfirmDialog} (câu lấy
 * nguyên từ {@code StatusAction}, nút xác nhận là nhãn của chính nút đã bấm, nút từ chối "Giữ đơn hàng" cho "Hủy
 * đơn" và "Quay lại" cho nút khác), service chưa được gọi tới khi người dùng xác nhận; không có câu xác nhận thì
 * gọi ngay. {@code StatusChangeResult} được ánh xạ bằng {@code switch} đủ nhánh (thêm một nhánh mà quên xử lý thì
 * không biên dịch được), rồi đơn được nạp lại tại chỗ. {@link #busyProperty()} giữ {@code true} từ lúc gửi tới khi
 * đơn được nạp lại nên bấm đúp không gửi hai lần; màn không khóa nút đang có focus.
 *
 * <p>Lỗi lạ đi vào {@link AdminFailures} (hộp thoại lỗi chung) và mở khóa. Sau {@link #dispose} không callback nào
 * đổi property hay điều hướng nữa (AC-009-10); việc đổi trạng thái đang chạy không bị hủy, nên toast kết quả vẫn
 * hiện (đơn đã thật sự đổi). Presenter chỉ import {@code javafx.beans}.
 */
@Component
@Scope("prototype")
class AdminOrderDetailPresenter {

    static final String MSG_CHANGED = "Đã chuyển đơn hàng #%d sang trạng thái %s.";
    static final String MSG_CANCELLED = "Đã hủy đơn hàng #%d và hoàn lại tồn kho.";
    static final String MSG_NOT_ALLOWED = "Không thể chuyển đơn hàng #%d từ %s sang %s.";
    static final String MSG_INVALID_STATUS = "Trạng thái không hợp lệ.";
    /** Nút từ chối của hộp xác nhận "Hủy đơn" (câu hỏi 4 của D5, dùng lại câu của D4). */
    static final String DISMISS_CANCEL = "Giữ đơn hàng";
    /** Nút từ chối của hộp xác nhận "Xác nhận đã giao" (câu hỏi 4 của D5). */
    static final String DISMISS_BACK = "Quay lại";

    private static final String CANCEL_TARGET = "CANCELLED";
    private static final VndFormatter MONEY = new VndFormatter();
    private static final VnDateTimeFormatter DATES = new VnDateTimeFormatter();

    private final OrderAdminService orderAdminService;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final Toasts toasts;
    private final ConfirmDialog confirmDialog;
    private final AdminFailures failures;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final StringProperty titleText = new SimpleStringProperty("");
    private final ObjectProperty<Detail> detail = new SimpleObjectProperty<>();

    private AdminOrderDetailRoute route;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    AdminOrderDetailPresenter(OrderAdminService orderAdminService, BackgroundTasks tasks, Navigator navigator,
                              Toasts toasts, ConfirmDialog confirmDialog, AdminFailures failures) {
        this.orderAdminService = orderAdminService;
        this.tasks = tasks;
        this.navigator = navigator;
        this.toasts = toasts;
        this.confirmDialog = confirmDialog;
        this.failures = failures;
    }

    /**
     * Một dòng hàng của đơn (giá và tên đã chụp lúc mua), chữ đã định dạng sẵn.
     *
     * @param productName   tên sản phẩm lúc mua
     * @param unitPriceText đơn giá lúc mua
     * @param quantityText  số lượng
     * @param lineTotalText thành tiền
     */
    record LineRow(String productName, String unitPriceText, String quantityText, String lineTotalText) {
    }

    /**
     * Toàn bộ chữ của màn cho một lần nạp.
     *
     * @param id           id đơn
     * @param statusLabel  nhãn trạng thái
     * @param statusKey    khóa chữ thường cho style class ({@code pending}...)
     * @param receiverName họ tên người nhận
     * @param phone        số điện thoại
     * @param address      địa chỉ giao hàng
     * @param note         ghi chú; {@code ""} khi không có
     * @param hasNote      {@code true} khi có ghi chú (màn ẩn dòng "Ghi chú" khi không)
     * @param paymentText  phương thức thanh toán
     * @param createdText  "Ngày đặt"
     * @param updatedText  "Cập nhật lần cuối"
     * @param accountText  "Tài khoản đặt hàng": "{họ tên} ({email})"
     * @param lines        dòng hàng theo thứ tự của đơn
     * @param totalText    "Tổng cộng"
     * @param actions      nút đổi trạng thái đúng như {@code AdminOrderDetail.actions()}
     * @param finalStatus  {@code true} khi không còn nút nào ("Đơn hàng đã ở trạng thái cuối.")
     */
    record Detail(long id, String statusLabel, String statusKey, String receiverName, String phone, String address,
                  String note, boolean hasNote, String paymentText, String createdText, String updatedText,
                  String accountText, List<LineRow> lines, String totalText, List<StatusAction> actions,
                  boolean finalStatus) {
    }

    /** Nội dung toast của một kết quả đổi trạng thái. */
    private record Outcome(String message, ToastKind kind) {
    }

    /** Nhận route của màn và nạp đơn ở nền. */
    void load(AdminOrderDetailRoute newRoute) {
        this.route = Objects.requireNonNull(newRoute, "route");
        titleText.set("Đơn hàng #" + newRoute.orderId());
        reload();
    }

    private void reload() {
        if (disposed || route == null) {
            return;
        }
        if (pending != null) {
            pending.cancel();
        }
        long orderId = route.orderId();
        loading.set(true);
        pending = tasks.submit(() -> orderAdminService.getOrder(orderId), this::show, this::loadFailed);
    }

    private void show(AdminOrderDetail view) {
        List<LineRow> lines = view.lines().stream().map(AdminOrderDetailPresenter::toLine).toList();
        boolean hasNote = view.note() != null && !view.note().isBlank();
        titleText.set("Đơn hàng #" + view.id());
        detail.set(new Detail(view.id(), view.statusLabel(), view.statusKey(), view.receiverName(), view.phone(),
                view.shippingAddress(), hasNote ? view.note() : "", hasNote, view.paymentMethodLabel(),
                DATES.format(view.createdAt()), DATES.format(view.updatedAt()),
                view.customerName() + " (" + view.customerEmail() + ")", lines,
                MONEY.format(view.totalAmount()), List.copyOf(view.actions()), view.isFinalStatus()));
        busy.set(false);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        busy.set(false);
        failures.handle(failure, AdminFailures.MSG_ORDER_NOT_FOUND, this::back);
    }

    private static LineRow toLine(OrderLineView line) {
        return new LineRow(line.productName(), MONEY.format(line.unitPrice()), Integer.toString(line.quantity()),
                MONEY.format(line.lineTotal()));
    }

    // ------------------------------------------------------------------ đổi trạng thái

    /**
     * Bấm một nút trạng thái. Có {@code confirmMessage} thì chỉ mở hộp xác nhận (chưa gọi service); không có thì
     * gửi ngay. Bỏ qua khi đang gửi, khi chưa có dữ liệu hoặc khi nút không còn trong danh sách hiện tại.
     */
    void runAction(StatusAction action) {
        Detail current = detail.get();
        if (disposed || route == null || current == null || busy.get() || action == null
                || !current.actions().contains(action)) {
            return;
        }
        String message = action.confirmMessage();
        if (message == null || message.isBlank()) {
            change(action.targetStatus());
            return;
        }
        String dismiss = CANCEL_TARGET.equals(action.targetStatus()) ? DISMISS_CANCEL : DISMISS_BACK;
        confirmDialog.ask(message, action.label(), dismiss, () -> change(action.targetStatus()));
    }

    /** Gửi lệnh đổi trạng thái (sau xác nhận nếu cần). Gọi lần hai khi đang gửi không làm gì. */
    private void change(String targetStatus) {
        if (disposed || route == null || busy.get()) {
            return;
        }
        long orderId = route.orderId();
        busy.set(true);
        // Không lưu vào pending: dispose không được hủy callback này, để người dùng luôn biết kết quả
        tasks.submit(() -> orderAdminService.changeStatus(orderId, targetStatus),
                result -> changed(orderId, result), this::changeFailed);
    }

    private void changed(long orderId, StatusChangeResult result) {
        // Biểu thức switch: thêm một nhánh vào StatusChangeResult mà quên xử lý thì không biên dịch được
        Outcome outcome = switch (result) {
            case StatusChangeResult.Changed changed ->
                    new Outcome(MSG_CHANGED.formatted(orderId, changed.statusLabel()), ToastKind.SUCCESS);
            case StatusChangeResult.Cancelled cancelled ->
                    new Outcome(MSG_CANCELLED.formatted(orderId), ToastKind.SUCCESS);
            case StatusChangeResult.NotAllowed notAllowed ->
                    new Outcome(MSG_NOT_ALLOWED.formatted(orderId, notAllowed.fromLabel(), notAllowed.toLabel()),
                            ToastKind.ERROR);
            case StatusChangeResult.InvalidStatus invalid -> new Outcome(MSG_INVALID_STATUS, ToastKind.ERROR);
        };
        toasts.show(outcome.message(), outcome.kind());
        if (disposed) {
            return;
        }
        // Mọi nhánh nạp lại tại chỗ; busy giữ tới khi có dữ liệu mới (show hoặc loadFailed mở khóa)
        reload();
    }

    private void changeFailed(Throwable failure) {
        if (disposed) {
            // Người dùng đã rời màn: không toast, không điều hướng; chỉ lỗi lạ mới được báo
            if (!failures.isAccessDenied(failure) && !(failure instanceof NotFoundException)) {
                failures.handle(failure);
            }
            return;
        }
        busy.set(false);
        failures.handle(failure, AdminFailures.MSG_ORDER_NOT_FOUND, this::back);
        if (!failures.isAccessDenied(failure) && !(failure instanceof NotFoundException)) {
            reload(); // lỗi lạ: không biết lệnh đã ghi chưa, nạp lại để màn hiện đúng trạng thái thật
        }
    }

    /** "Quay lại danh sách đơn": về trang danh sách đã dẫn tới màn này, giữ bộ lọc và trang. */
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

    // ------------------------------------------------------------------ property cho màn

    /** {@code true} trong lúc đang nạp (lần đầu hoặc nạp lại sau khi đổi trạng thái). */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lúc gửi lệnh đổi trạng thái tới khi đơn được nạp lại. */
    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    /** "Đơn hàng #{id}"; có ngay từ khi nhận route. */
    ReadOnlyStringProperty titleTextProperty() {
        return titleText;
    }

    /** Dữ liệu đơn hiện tại; {@code null} tới lần nạp thành công đầu tiên. Đổi một lần mỗi lần nạp. */
    ReadOnlyObjectProperty<Detail> detailProperty() {
        return detail;
    }
}
