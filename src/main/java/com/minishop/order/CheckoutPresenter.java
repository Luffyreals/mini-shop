package com.minishop.order;

import com.minishop.cart.CartBadge;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.DrawerHost;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import com.minishop.desktop.ToastKind;
import com.minishop.desktop.Toasts;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Trạng thái và quyết định của màn Thanh toán (QĐ8, QĐ9, QĐ10 của D4): form giao hàng, tóm tắt đơn, khi nào nút
 * "Xác nhận đặt hàng" bấm được, thông báo nào hiện và đi đâu sau mỗi kết quả. Không import
 * {@code javafx.scene}; {@link CheckoutScreen} chỉ dựng node và gắn vào các property này. Mỗi lần điều hướng tới
 * {@link CheckoutRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần và {@link #dispose}
 * khi bị thay.
 *
 * <p><b>Luồng:</b> mọi phương thức gọi trên luồng giao diện. Gọi service qua {@link BackgroundTasks}, người dùng
 * đọc từ {@link AppSession} <em>trên luồng giao diện trước khi gửi việc</em> và truyền id vào service (BR-009-4).
 * Phiên là khách (không xảy ra qua giao diện) thì về trang chủ, không gọi service. Không điều hướng đồng bộ trong
 * {@code load}: {@code Navigator} gắn node của màn đang dựng sau khi {@code create} trả về, nên việc về trang chủ
 * của phiên khách đi qua một việc nền rỗng để chạy ở callback.
 *
 * <p><b>Tóm tắt luôn khớp giỏ (QĐ8):</b> presenter nghe {@link DrawerHost#showingProperty()}; khi drawer đóng thì
 * nạp lại {@code checkoutPage} nhưng chỉ lấy tóm tắt, tổng và {@code expectedTotal}, giữ chữ đã nhập. Form chỉ được
 * điền sẵn ở lần nạp đầu (QĐ10). {@code expectedTotal} là trường riêng, luôn bằng tổng đang hiện, không đọc từ ô
 * nào.
 *
 * <p><b>Đặt hàng (QĐ9):</b> {@link #confirm()} kiểm form bằng {@link Validator} (mỗi ô một lỗi, không gọi service),
 * rồi khóa form và nút ({@link #busyProperty()}) và gọi {@code placeOrder(userId, form, expectedTotal)}. Kết quả
 * {@link PlaceOrderException} được bắt ở {@code onFailure}, sau khi transaction đã xong, và ánh xạ bằng
 * {@code switch} đủ nhánh. Rời màn trong lúc đang đặt không hủy việc đặt (hủy callback không hủy transaction):
 * {@link #dispose()} không hủy việc đó; xong mà màn đã bị thay thì thành công vẫn làm mới badge và báo bằng toast,
 * không điều hướng. {@link CartBadge#refresh()} được gọi sau mọi kết quả của {@code placeOrder} (QĐ4).
 *
 * <p>Presenter không ghi log và không bao giờ đưa nội dung form vào thông báo.
 */
@Component
@Scope("prototype")
class CheckoutPresenter {

    static final String MSG_NOT_ORDERABLE =
            "Giỏ hàng có sản phẩm không đủ hàng. Vui lòng cập nhật giỏ trước khi đặt hàng.";
    static final String MSG_INSUFFICIENT_STOCK = "Sản phẩm %s không đủ hàng. Vui lòng cập nhật giỏ hàng.";
    static final String MSG_PRICE_CHANGED =
            "Giá sản phẩm vừa thay đổi. Vui lòng kiểm tra lại đơn hàng trước khi xác nhận.";
    static final String MSG_EMPTY_CART = "Giỏ hàng đang trống nên chưa có đơn mới nào được tạo.";
    static final String MSG_PLACED = "Đặt hàng thành công. Mã đơn của bạn là #%d.";

    /** Ô của form; dùng để màn đưa focus tới ô lỗi đầu tiên. */
    enum Field { RECEIVER_NAME, PHONE, ADDRESS, NOTE }

    /** Loại thông báo trên màn; chỉ đổi biểu tượng, chữ luôn là ink. */
    enum NoticeKind { SUCCESS, ERROR }

    private static final VndFormatter PRICE = new VndFormatter();

    private final OrderService orders;
    private final AppSession session;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final ShellPresenter shell;
    private final DrawerHost drawer;
    private final Toasts toasts;
    private final CartBadge cartBadge;
    private final UnexpectedErrors errors;
    private final Validator validator;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty ready = new SimpleBooleanProperty(false);
    private final BooleanProperty orderable = new SimpleBooleanProperty(false);
    private final ObservableList<CheckoutLineView> lines = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper totalText = new ReadOnlyStringWrapper("");

    private final StringProperty receiverName = new SimpleStringProperty("");
    private final StringProperty phone = new SimpleStringProperty("");
    private final StringProperty shippingAddress = new SimpleStringProperty("");
    private final StringProperty note = new SimpleStringProperty("");
    private final ReadOnlyStringWrapper receiverNameError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper phoneError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper shippingAddressError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper noteError = new ReadOnlyStringWrapper("");

    private final ReadOnlyStringWrapper noticeText = new ReadOnlyStringWrapper("");
    private final ObjectProperty<NoticeKind> noticeKind = new SimpleObjectProperty<>(NoticeKind.ERROR);
    private final ObjectProperty<Field> firstErrorField = new SimpleObjectProperty<>();
    /** Không gồm {@code busy}: nút giữ được focus trong lúc đặt hàng, và {@link #confirm()} tự bỏ qua lần bấm thứ hai. */
    private final BooleanBinding confirmDisabled = loading.or(orderable.not());

    private final ChangeListener<Boolean> drawerListener = (observable, wasShowing, isShowing) -> {
        if (!isShowing) {
            onDrawerClosed();
        }
    };

    /** Tổng đang hiện; là số sẽ được so với giỏ lúc đặt (BR-005-5). Không bao giờ đọc từ ô nhập. */
    private BigDecimal expectedTotal;
    private BackgroundTasks.Handle pendingLoad;
    private boolean loadedOnce;
    private boolean reloadWanted;
    private boolean disposed;

    CheckoutPresenter(OrderService orders, AppSession session, BackgroundTasks tasks, Navigator navigator,
                      ShellPresenter shell, DrawerHost drawer, Toasts toasts, CartBadge cartBadge,
                      UnexpectedErrors errors, Validator validator) {
        this.orders = orders;
        this.session = session;
        this.tasks = tasks;
        this.navigator = navigator;
        this.shell = shell;
        this.drawer = drawer;
        this.toasts = toasts;
        this.cartBadge = cartBadge;
        this.errors = errors;
        this.validator = validator;
        // Gõ lại vào một ô thì lỗi của ô đó biến mất
        receiverName.addListener((observable, oldValue, newValue) -> receiverNameError.set(""));
        phone.addListener((observable, oldValue, newValue) -> phoneError.set(""));
        shippingAddress.addListener((observable, oldValue, newValue) -> shippingAddressError.set(""));
        note.addListener((observable, oldValue, newValue) -> noteError.set(""));
    }

    // ------------------------------------------------------------------ nạp

    /**
     * Nạp tóm tắt và form điền sẵn ở nền (lần đầu), và bắt đầu nghe drawer đóng. Giỏ trống: về trang chủ rồi mở
     * drawer (AC-005-4); giỏ có dòng không đặt được: thông báo, toast, mở drawer và khóa nút (AC-005-5).
     */
    void load() {
        if (disposed || loadedOnce || pendingLoad != null) {
            return;
        }
        drawer.showingProperty().addListener(drawerListener);
        Optional<CurrentUser> user = session.currentUser();
        if (user.isEmpty()) {
            // Không điều hướng đồng bộ trong ScreenFactory.create; việc rỗng để callback chạy sau khi màn đã gắn
            pendingLoad = tasks.submit(() -> Boolean.TRUE, done -> navigator.goHome());
            return;
        }
        long userId = user.get().id();
        loading.set(true);
        pendingLoad = tasks.submit(() -> orders.checkoutPage(userId), this::onLoaded, this::onLoadFailed);
    }

    /** Drawer vừa đóng: nạp lại tóm tắt, giữ chữ đã nhập (QĐ8). Đang đặt hàng thì hoãn tới khi xong. */
    void reloadSummary() {
        if (disposed || !loadedOnce) {
            return;
        }
        if (busy.get()) {
            reloadWanted = true;
            return;
        }
        Optional<CurrentUser> user = session.currentUser();
        if (user.isEmpty()) {
            navigator.goHome();
            return;
        }
        cancelLoad();
        long userId = user.get().id();
        loading.set(true);
        pendingLoad = tasks.submit(() -> orders.checkoutPage(userId), this::onLoaded, this::onLoadFailed);
    }

    private void onDrawerClosed() {
        reloadSummary();
    }

    private void onLoaded(CheckoutPage page) {
        pendingLoad = null;
        loading.set(false);
        if (disposed || busy.get()) {
            return;
        }
        boolean initial = !loadedOnce;
        loadedOnce = true;
        if (initial) {
            CheckoutForm prefill = page.prefill();
            receiverName.set(nullToEmpty(prefill.receiverName()));
            phone.set(nullToEmpty(prefill.phone()));
        }
        applySummary(page.checkout(), initial);
    }

    private void onLoadFailed(Throwable failure) {
        pendingLoad = null;
        loading.set(false);
        errors.report(failure);
        if (!disposed && !loadedOnce) {
            // Lần nạp đầu hỏng thì màn không có gì để hiện và không tự nạp lại: về trang chủ để không bị kẹt
            navigator.goHome();
        }
    }

    /**
     * Đưa một tóm tắt lên màn: thay dòng, tổng và {@code expectedTotal}. Giỏ trống thì về trang chủ (kèm mở drawer
     * ở lần nạp đầu); không đặt được thì khóa nút và báo.
     */
    private void applySummary(CheckoutView view, boolean initial) {
        clearNotice();
        if (view.isEmpty()) {
            lines.clear();
            orderable.set(false);
            navigator.goHome();
            if (initial) {
                shell.trigger(ShellAction.CART);
            }
            return;
        }
        lines.setAll(view.lines());
        expectedTotal = view.total();
        totalText.set(PRICE.format(view.total()));
        orderable.set(view.orderable());
        ready.set(true);
        if (!view.orderable()) {
            showNotice(MSG_NOT_ORDERABLE, NoticeKind.ERROR);
            if (initial) {
                toasts.show(MSG_NOT_ORDERABLE, ToastKind.ERROR);
                shell.trigger(ShellAction.CART);
            }
        }
    }

    // ------------------------------------------------------------------ xác nhận đặt hàng

    /**
     * "Xác nhận đặt hàng". Form sai: mỗi ô một lỗi, {@link #firstErrorFieldProperty()} cho biết ô cần focus, không
     * gọi service. Form đúng: khóa và gửi {@code placeOrder} đúng một lần với tổng đang hiện.
     */
    void confirm() {
        if (disposed || busy.get() || loading.get() || !orderable.get() || expectedTotal == null) {
            return;
        }
        clearErrors();
        clearNotice();
        CheckoutForm form = new CheckoutForm(receiverName.get(), phone.get(), shippingAddress.get(), note.get());
        Set<ConstraintViolation<CheckoutForm>> violations = validator.validate(form);
        if (!violations.isEmpty()) {
            showViolations(violations);
            return;
        }
        Optional<CurrentUser> user = session.currentUser();
        if (user.isEmpty()) {
            navigator.goHome();
            return;
        }
        long userId = user.get().id();
        BigDecimal shownTotal = expectedTotal;
        busy.set(true);
        // Handle không được giữ: dispose() không hủy việc đặt hàng (QĐ9)
        tasks.submit(() -> orders.placeOrder(userId, form, shownTotal), this::onPlaced, this::onPlaceFailed);
    }

    private void onPlaced(Long orderId) {
        cartBadge.refresh();
        if (disposed) {
            // Người dùng đã đi chỗ khác nhưng đơn vẫn được đặt: báo cho họ biết, không điều hướng
            toasts.show(MSG_PLACED.formatted(orderId), ToastKind.SUCCESS);
            return;
        }
        // busy giữ nguyên tới khi màn bị thay, để không thể bấm lần hai
        navigator.navigateTo(new OrderDetailRoute(orderId, OrdersRoute.first(), true));
    }

    private void onPlaceFailed(Throwable failure) {
        if (!(failure instanceof PlaceOrderException expected)) {
            if (!disposed) {
                busy.set(false);
            }
            errors.report(failure);
            runDeferredReload();
            return;
        }
        cartBadge.refresh();
        if (disposed) {
            return; // không có gì thay đổi: không đơn mới, kho nguyên
        }
        busy.set(false);
        switch (expected) {
            case PlaceOrderException.EmptyCart _ -> {
                toasts.show(MSG_EMPTY_CART, ToastKind.INFO);
                navigator.navigateTo(OrdersRoute.first());
            }
            case PlaceOrderException.NotOrderable _ -> cannotOrder(MSG_NOT_ORDERABLE);
            case PlaceOrderException.InsufficientStock stock ->
                    cannotOrder(MSG_INSUFFICIENT_STOCK.formatted(stock.productName()));
            case PlaceOrderException.PriceChanged changed -> {
                applySummary(changed.current(), false);
                if (orderable.get()) {
                    showNotice(MSG_PRICE_CHANGED, NoticeKind.ERROR);
                }
            }
        }
        runDeferredReload();
    }

    /** Drawer đã đóng trong lúc đang đặt hàng: giờ đặt xong (hay hỏng) thì nạp lại tóm tắt. */
    private void runDeferredReload() {
        if (reloadWanted && !disposed) {
            reloadWanted = false;
            reloadSummary();
        }
    }

    /** Hết hàng hoặc ngừng bán lúc xác nhận: toast, mở drawer để sửa giỏ; màn ở lại và nạp lại khi drawer đóng. */
    private void cannotOrder(String message) {
        orderable.set(false);
        showNotice(MSG_NOT_ORDERABLE, NoticeKind.ERROR);
        toasts.show(message, ToastKind.ERROR);
        shell.trigger(ShellAction.CART);
    }

    /** "Quay lại giỏ hàng": mở drawer trên màn Thanh toán, form giữ nguyên. */
    void backToCart() {
        if (disposed || busy.get()) {
            return;
        }
        shell.trigger(ShellAction.CART);
    }

    // ------------------------------------------------------------------ lỗi form

    private void clearErrors() {
        receiverNameError.set("");
        phoneError.set("");
        shippingAddressError.set("");
        noteError.set("");
        firstErrorField.set(null);
    }

    /**
     * Mỗi ô một lỗi, chọn theo thứ tự cố định: NotBlank trước, rồi Size/PhoneNumber; cùng hạng thì câu đứng trước
     * theo chữ cái (kết quả ổn định). Ô lỗi đầu tiên theo thứ tự trên màn được báo để focus.
     */
    private void showViolations(Set<ConstraintViolation<CheckoutForm>> violations) {
        Comparator<ConstraintViolation<CheckoutForm>> order = Comparator
                .comparingInt(CheckoutPresenter::rank)
                .thenComparing(ConstraintViolation::getMessage);
        Map<String, ConstraintViolation<CheckoutForm>> first = new HashMap<>();
        for (ConstraintViolation<CheckoutForm> violation : violations) {
            first.merge(violation.getPropertyPath().toString(), violation,
                    (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
        Field focus = null;
        focus = setError(receiverNameError, first.get("receiverName"), Field.RECEIVER_NAME, focus);
        focus = setError(phoneError, first.get("phone"), Field.PHONE, focus);
        focus = setError(shippingAddressError, first.get("shippingAddress"), Field.ADDRESS, focus);
        focus = setError(noteError, first.get("note"), Field.NOTE, focus);
        if (focus == null) {
            // Một ràng buộc không gắn được vào ô nào: lỗi lập trình, không để người dùng nhìn form im lặng
            errors.report(new IllegalStateException("Checkout violation without a field"));
            return;
        }
        firstErrorField.set(focus);
    }

    private static Field setError(ReadOnlyStringWrapper target, ConstraintViolation<CheckoutForm> violation,
                                  Field field, Field firstSoFar) {
        if (violation == null) {
            return firstSoFar;
        }
        target.set(violation.getMessage());
        return firstSoFar != null ? firstSoFar : field;
    }

    private static int rank(ConstraintViolation<CheckoutForm> violation) {
        return violation.getConstraintDescriptor().getAnnotation().annotationType() == NotBlank.class ? 0 : 1;
    }

    // ------------------------------------------------------------------ thông báo trên màn

    private void showNotice(String text, NoticeKind kind) {
        noticeKind.set(kind);
        noticeText.set(text);
    }

    private void clearNotice() {
        noticeText.set("");
    }

    // ------------------------------------------------------------------ vòng đời

    private void cancelLoad() {
        if (pendingLoad != null) {
            pendingLoad.cancel();
            pendingLoad = null;
        }
    }

    /**
     * Hủy việc nạp đang chờ và gỡ listener của drawer. <b>Không</b> hủy việc đặt hàng đang chạy (QĐ9). Gọi nhiều
     * lần đều vô hại.
     */
    void dispose() {
        disposed = true;
        cancelLoad();
        drawer.showingProperty().removeListener(drawerListener);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Tiền định dạng cho màn (một chỗ duy nhất định dạng VND). */
    String money(BigDecimal amount) {
        return PRICE.format(amount);
    }

    // ------------------------------------------------------------------ property cho CheckoutScreen

    /** {@code true} trong lúc nạp (lần đầu hoặc nạp lại sau khi drawer đóng). */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} trong lúc {@code placeOrder} chạy (và sau khi đặt xong, tới khi màn bị thay). */
    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    /** {@code true} sau lần nạp đầu có hàng; trước đó màn chỉ hiện vòng chờ. */
    ReadOnlyBooleanProperty readyProperty() {
        return ready;
    }

    /** Mọi dòng của giỏ đặt được; {@code false} thì nút xác nhận khóa. */
    ReadOnlyBooleanProperty orderableProperty() {
        return orderable;
    }

    /**
     * Nút "Xác nhận đặt hàng" khóa khi đang nạp hoặc giỏ không đặt được. Đang đặt thì nút vẫn mở (để focus không bị
     * đẩy sang nút khác và Enter lần hai không làm gì): {@link #confirm()} bỏ qua khi {@link #busyProperty()}.
     */
    BooleanBinding confirmDisabledBinding() {
        return confirmDisabled;
    }

    ObservableList<CheckoutLineView> lines() {
        return lines;
    }

    /** "Tổng cộng" đã định dạng, ví dụ {@code 2.649.000 ₫}. */
    ReadOnlyStringProperty totalTextProperty() {
        return totalText.getReadOnlyProperty();
    }

    StringProperty receiverNameProperty() {
        return receiverName;
    }

    StringProperty phoneProperty() {
        return phone;
    }

    StringProperty shippingAddressProperty() {
        return shippingAddress;
    }

    StringProperty noteProperty() {
        return note;
    }

    ReadOnlyStringProperty receiverNameErrorProperty() {
        return receiverNameError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty phoneErrorProperty() {
        return phoneError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty shippingAddressErrorProperty() {
        return shippingAddressError.getReadOnlyProperty();
    }

    ReadOnlyStringProperty noteErrorProperty() {
        return noteError.getReadOnlyProperty();
    }

    /** Thông báo trên màn; rỗng khi không có. */
    ReadOnlyStringProperty noticeTextProperty() {
        return noticeText.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<NoticeKind> noticeKindProperty() {
        return noticeKind;
    }

    /**
     * Ô cần focus sau một lần kiểm form sai; đặt về {@code null} rồi mới đặt giá trị mới ở mỗi lần, nên màn nghe
     * thay đổi và chỉ xử lý giá trị khác {@code null}.
     */
    ReadOnlyObjectProperty<Field> firstErrorFieldProperty() {
        return firstErrorField;
    }
}
