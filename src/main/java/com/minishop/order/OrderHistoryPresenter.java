package com.minishop.order;

import com.minishop.catalog.CatalogRoute;
import com.minishop.common.PageNav;
import com.minishop.common.VnDateTimeFormatter;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.UnexpectedErrors;
import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn "Đơn hàng của tôi" (AC-005-20 đến -22, QĐ2 của D4). Mỗi lần điều hướng tới một
 * {@link OrdersRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần và {@link #dispose}
 * khi bị thay.
 *
 * <p><b>Luồng:</b> {@code OrderService.listOrders} chạy qua {@link BackgroundTasks}; property chỉ đổi trên luồng
 * giao diện. Người dùng được đọc từ {@link AppSession} trên luồng giao diện trước khi gửi việc và chỉ {@code userId}
 * đi vào service (BR-009-4). Trang truyền cho service là chữ số của route; trang vượt cuối thì service trả trang 1
 * và {@link #pageNavProperty()} theo trang service trả về. Đổi trang là điều hướng tới một route mới (route là
 * nguồn sự thật), nên màn và presenter được dựng lại.
 *
 * <p>Lỗi lạ đi vào {@link UnexpectedErrors}. Sau {@link #dispose} không callback nào đổi property hay điều hướng
 * nữa. Presenter chỉ import {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class OrderHistoryPresenter {

    private static final VndFormatter MONEY = new VndFormatter();
    private static final VnDateTimeFormatter DATES = new VnDateTimeFormatter();

    private final OrderService orderService;
    private final AppSession session;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final UnexpectedErrors errors;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty empty = new SimpleBooleanProperty(false);
    private final ObservableList<OrderRow> rows = FXCollections.observableArrayList();
    private final ObjectProperty<PageNav> pageNav = new SimpleObjectProperty<>();

    private int currentPage = 1;
    private BackgroundTasks.Handle pending;
    private boolean disposed;

    OrderHistoryPresenter(OrderService orderService, AppSession session, BackgroundTasks tasks,
                          Navigator navigator, UnexpectedErrors errors) {
        this.orderService = orderService;
        this.session = session;
        this.tasks = tasks;
        this.navigator = navigator;
        this.errors = errors;
    }

    /**
     * Một hàng của danh sách, chữ đã định dạng sẵn.
     *
     * @param id             id đơn (để mở chi tiết)
     * @param idText         "#{id}"
     * @param dateText       ngày đặt, giờ Việt Nam ({@code dd/MM/yyyy HH:mm})
     * @param totalText      tổng tiền, ví dụ {@code 1.250.000 ₫}
     * @param statusLabel    nhãn trạng thái bằng chữ
     * @param statusKey      khóa chữ thường dùng cho style class ({@code pending}, {@code cancelled}...)
     * @param accessibleText "Đơn hàng #{id}, {ngày đặt}, {tổng tiền}, {trạng thái}"
     */
    record OrderRow(long id, String idText, String dateText, String totalText, String statusLabel,
                    String statusKey, String accessibleText) {
    }

    /** Nhận route của màn và nạp trang đơn hàng ở nền. */
    void load(OrdersRoute route) {
        Objects.requireNonNull(route, "route");
        CurrentUser user = session.currentUser().orElse(null);
        if (user == null) {
            // Không xảy ra qua giao diện (menu chỉ có khi đăng nhập). Không gọi service; về trang chủ sau khi màn
            // được dựng xong (điều hướng lồng trong ScreenFactory.create sẽ bị Navigator ghi đè)
            pending = tasks.submit(() -> Boolean.TRUE, done -> navigator.goHome());
            return;
        }
        long userId = user.id();
        String rawPage = Integer.toString(route.page());
        loading.set(true);
        pending = tasks.submit(() -> orderService.listOrders(userId, rawPage), this::show, this::loadFailed);
    }

    private void show(OrderListView view) {
        currentPage = view.page();
        rows.setAll(view.orders().stream().map(OrderHistoryPresenter::toRow).toList());
        empty.set(view.isEmpty());
        pageNav.set(PageNav.of(view.page(), view.totalPages()));
        loaded.set(true);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        errors.report(failure);
    }

    private static OrderRow toRow(OrderSummaryView order) {
        String idText = "#" + order.id();
        String date = DATES.format(order.createdAt());
        String total = MONEY.format(order.totalAmount());
        String accessible = "Đơn hàng " + idText + ", " + date + ", " + total + ", " + order.statusLabel();
        return new OrderRow(order.id(), idText, date, total, order.statusLabel(), order.statusKey(), accessible);
    }

    /** Đổi sang trang {@code page} (từ 1): điều hướng tới {@link OrdersRoute} mới. Số nhỏ hơn 1 bị bỏ qua. */
    void goToPage(int page) {
        if (disposed || page < 1) {
            return;
        }
        navigator.navigateTo(new OrdersRoute(page));
    }

    /** Mở chi tiết đơn; "Quay lại danh sách đơn" sẽ về đúng trang đang xem. */
    void openOrder(long orderId) {
        if (disposed) {
            return;
        }
        navigator.navigateTo(new OrderDetailRoute(orderId, new OrdersRoute(currentPage), false));
    }

    /** "Bắt đầu mua sắm" ở trạng thái rỗng (AC-005-22): mở "Hàng mới". */
    void startShopping() {
        if (disposed) {
            return;
        }
        navigator.navigateTo(CatalogRoute.all());
    }

    /** Hủy việc đang chờ: sau đó không callback nào của màn này chạy nữa. Gọi nhiều lần đều vô hại. */
    void dispose() {
        disposed = true;
        if (pending != null) {
            pending.cancel();
        }
    }

    /** {@code true} trong lúc đang nạp. */
    ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    /** {@code true} từ lần nạp thành công đầu tiên. */
    ReadOnlyBooleanProperty loadedProperty() {
        return loaded;
    }

    /** {@code true} khi người dùng chưa có đơn nào ("Bạn chưa có đơn hàng nào."). */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty;
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
