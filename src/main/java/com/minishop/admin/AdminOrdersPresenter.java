package com.minishop.admin;

import com.minishop.common.PageNav;
import com.minishop.common.VnDateTimeFormatter;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.order.AdminOrderPage;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.StatusFilterOption;
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
 * Trạng thái và quyết định của màn "Đơn hàng" của admin (AC-006-38, -39, F5 của D5). Mỗi lần điều hướng tới một
 * {@link AdminOrdersRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần và
 * {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> {@code OrderAdminService.listOrders(status, page)} chạy qua {@link BackgroundTasks}, trạng thái
 * và trang truyền dạng chuỗi (service tự coi giá trị lạ là "Tất cả" / trang 1, và trang vượt cuối là trang 1).
 * Property chỉ đổi trên luồng giao diện, một lần, khi đã có dữ liệu; {@link #loadedProperty()} đổi cuối cùng.
 * Đổi bộ lọc hay trang là điều hướng tới một route mới (route là nguồn sự thật), nên màn và presenter được dựng lại.
 *
 * <p>Lỗi đi vào {@link AdminFailures} (phiên không phải ADMIN: toast và về trang chủ; lỗi lạ: hộp thoại lỗi chung).
 * Sau {@link #dispose} không callback nào đổi property hay điều hướng nữa (AC-009-10). Presenter chỉ import
 * {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class AdminOrdersPresenter {

    /** Câu rỗng khi bộ lọc là "Tất cả" (AC-006-38). */
    static final String EMPTY_ALL = "Chưa có đơn hàng nào.";
    /** Câu rỗng khi lọc theo một trạng thái; điền nhãn trạng thái (AC-006-39). */
    static final String EMPTY_FILTERED = "Không có đơn hàng nào ở trạng thái %s.";

    private static final VndFormatter MONEY = new VndFormatter();
    private static final VnDateTimeFormatter DATES = new VnDateTimeFormatter();

    private final OrderAdminService orderAdminService;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final AdminFailures failures;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final StringProperty emptyText = new SimpleStringProperty("");
    private final ObservableList<FilterOption> filters = FXCollections.observableArrayList();
    private final ObservableList<OrderRow> rows = FXCollections.observableArrayList();
    private final ObjectProperty<PageNav> pageNav = new SimpleObjectProperty<>();

    /** Route thật đang hiện (trạng thái và trang do service chuẩn hóa); {@code null} tới lần nạp đầu tiên. */
    private AdminOrdersRoute current;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    AdminOrdersPresenter(OrderAdminService orderAdminService, BackgroundTasks tasks, Navigator navigator,
                         AdminFailures failures) {
        this.orderAdminService = orderAdminService;
        this.tasks = tasks;
        this.navigator = navigator;
        this.failures = failures;
    }

    /**
     * Một pill của bộ lọc trạng thái.
     *
     * @param value    tên trạng thái ({@code PENDING}...) hoặc {@code null} cho "Tất cả"; truyền cho {@link #filter}
     * @param label    nhãn hiện trên pill ("Tất cả" hoặc nhãn BR-005-9)
     * @param selected {@code true} cho pill đang áp dụng
     */
    record FilterOption(String value, String label, boolean selected) {
    }

    /**
     * Một hàng của danh sách, chữ đã định dạng sẵn.
     *
     * @param id             id đơn (để mở chi tiết)
     * @param idText         "#{id}"
     * @param dateText       ngày đặt, giờ Việt Nam ({@code dd/MM/yyyy HH:mm})
     * @param customerName   họ tên tài khoản đặt hàng
     * @param customerEmail  email tài khoản đặt hàng
     * @param receiverName   họ tên người nhận
     * @param totalText      tổng tiền, ví dụ {@code 1.250.000 ₫}
     * @param statusLabel    nhãn trạng thái bằng chữ
     * @param statusKey      khóa chữ thường cho style class ({@code pending}...)
     * @param accessibleText "Đơn hàng #{id}, {ngày đặt}, {khách hàng}, {tổng tiền}, {trạng thái}"
     */
    record OrderRow(long id, String idText, String dateText, String customerName, String customerEmail,
                    String receiverName, String totalText, String statusLabel, String statusKey,
                    String accessibleText) {
    }

    /** Nhận route của màn và nạp trang đơn ở nền. */
    void load(AdminOrdersRoute route) {
        Objects.requireNonNull(route, "route");
        if (disposed || loading.get()) {
            return;
        }
        String rawStatus = route.status();
        String rawPage = Integer.toString(route.page());
        loading.set(true);
        pending = tasks.submit(() -> orderAdminService.listOrders(rawStatus, rawPage), this::show, this::loadFailed);
    }

    private void show(AdminOrderPage page) {
        current = new AdminOrdersRoute(page.status(), page.page());
        filters.setAll(page.filters().stream().map(AdminOrdersPresenter::toFilter).toList());
        rows.setAll(page.orders().stream().map(AdminOrdersPresenter::toRow).toList());
        emptyText.set(page.status() == null ? EMPTY_ALL : EMPTY_FILTERED.formatted(page.statusLabel()));
        empty.set(page.isEmpty());
        pageNav.set(PageNav.of(page.page(), page.totalPages()));
        loaded.set(true);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        failures.handle(failure);
    }

    private static FilterOption toFilter(StatusFilterOption option) {
        return new FilterOption(option.value(), option.label(), option.selected());
    }

    private static OrderRow toRow(AdminOrderRow order) {
        String idText = "#" + order.id();
        String date = DATES.format(order.createdAt());
        String total = MONEY.format(order.totalAmount());
        String accessible = "Đơn hàng " + idText + ", " + date + ", " + order.customerName() + ", " + total + ", "
                + order.statusLabel();
        return new OrderRow(order.id(), idText, date, order.customerName(), order.customerEmail(),
                order.receiverName(), total, order.statusLabel(), order.statusKey(), accessible);
    }

    // ------------------------------------------------------------------ lối đi

    /**
     * Bấm một pill: điều hướng tới route có bộ lọc đó, về trang 1. Bộ lọc đang áp dụng (kể cả khi đang ở trang
     * khác) thì không làm gì.
     *
     * @param value tên trạng thái hoặc {@code null} cho "Tất cả"
     */
    void filter(String value) {
        if (disposed || current == null) {
            return;
        }
        AdminOrdersRoute next = current.withStatus(value);
        if (Objects.equals(next.status(), current.status())) {
            return;
        }
        navigator.navigateTo(next);
    }

    /** Đổi sang trang {@code page} (từ 1), giữ bộ lọc. Số nhỏ hơn 1 hoặc đúng trang đang xem bị bỏ qua. */
    void goToPage(int page) {
        if (disposed || current == null || page < 1 || page == current.page()) {
            return;
        }
        navigator.navigateTo(current.withPage(page));
    }

    /** Mở chi tiết đơn; "Quay lại danh sách đơn" sẽ về đúng bộ lọc và trang đang xem (AC-009-13 cũng về đó). */
    void openOrder(long orderId) {
        if (disposed || current == null) {
            return;
        }
        navigator.navigateTo(new AdminOrderDetailRoute(orderId, current));
    }

    /** Hủy việc đang chờ: sau đó không callback nào của màn này chạy nữa. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
        }
    }

    // ------------------------------------------------------------------ property cho màn

    /** {@code true} trong lúc đang nạp. */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** {@code true} khi không có đơn nào khớp bộ lọc (không chỉ là trang rỗng). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
    }

    /** Câu rỗng theo bộ lọc: "Chưa có đơn hàng nào." hoặc "Không có đơn hàng nào ở trạng thái {nhãn}.". */
    ReadOnlyStringProperty emptyTextProperty() {
        return emptyText;
    }

    /** "Tất cả" và năm nhãn trạng thái (từ {@code AdminOrderPage.filters()}), cái đang áp dụng có {@code selected}. */
    ObservableList<FilterOption> filters() {
        return filters;
    }

    /** Các hàng của trang hiện tại, mới nhất trước (thứ tự do service quyết định). */
    ObservableList<OrderRow> rows() {
        return rows;
    }

    /** Dữ liệu phân trang theo trang service trả về; {@code null} tới lần nạp thành công đầu tiên. */
    ReadOnlyObjectProperty<PageNav> pageNavProperty() {
        return pageNav;
    }
}
