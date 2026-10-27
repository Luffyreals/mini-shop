package com.minishop.admin;

import com.minishop.catalog.LowStockRow;
import com.minishop.catalog.ProductAdminService;
import com.minishop.common.VnDateTimeFormatter;
import com.minishop.common.VndFormatter;
import com.minishop.desktop.BackgroundTasks;
import com.minishop.desktop.Navigator;
import com.minishop.order.AdminOrderRow;
import com.minishop.order.OrderAdminService;
import com.minishop.order.OrderDashboard;
import com.minishop.order.StatusCount;
import java.util.List;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Trạng thái và quyết định của màn "Tổng quan" (AC-006-6 đến -8, QĐ8 của D5). Mỗi lần điều hướng tới
 * {@link AdminDashboardRoute} có một presenter mới ({@code prototype}); màn gọi {@link #load} một lần và
 * {@link #dispose} khi bị thay.
 *
 * <p><b>Luồng:</b> một việc nền ({@link BackgroundTasks}) gọi hai service của hai tính năng khác nhau:
 * {@code OrderAdminService.dashboard()} rồi {@code ProductAdminService.lowStock()} (hai transaction chỉ đọc, đúng
 * BR-006-14), gói trong một record. Property chỉ đổi trên luồng giao diện, một lần, khi cả hai đã xong. Phiên
 * không phải ADMIN thì service ném {@code AccessDeniedException}: {@link AdminFailures} hiện toast và về trang chủ.
 *
 * <p>Sau {@link #dispose} không callback nào đổi property hay điều hướng nữa (AC-009-10). Presenter chỉ import
 * {@code javafx.beans} và {@code javafx.collections}.
 */
@Component
@Scope("prototype")
class AdminDashboardPresenter {

    private static final VndFormatter MONEY = new VndFormatter();
    private static final VnDateTimeFormatter DATES = new VnDateTimeFormatter();

    /** Tên trạng thái được tô viền accent ("Chờ xử lý": việc cần làm trước). */
    static final String LEAD_STATUS = "PENDING";

    private final OrderAdminService orderAdminService;
    private final ProductAdminService productAdminService;
    private final BackgroundTasks tasks;
    private final Navigator navigator;
    private final AdminFailures failures;

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final ObservableList<CountTile> counts = FXCollections.observableArrayList();
    private final StringProperty revenueText = new SimpleStringProperty("");
    private final ObservableList<LowStockItem> lowStock = FXCollections.observableArrayList();
    private final ObservableList<LatestOrder> latest = FXCollections.observableArrayList();
    private final BooleanProperty lowStockEmpty = new SimpleBooleanProperty(false);
    private final BooleanProperty latestEmpty = new SimpleBooleanProperty(false);

    private BackgroundTasks.Handle pending;
    private boolean disposed;

    AdminDashboardPresenter(OrderAdminService orderAdminService, ProductAdminService productAdminService,
                            BackgroundTasks tasks, Navigator navigator, AdminFailures failures) {
        this.orderAdminService = orderAdminService;
        this.productAdminService = productAdminService;
        this.tasks = tasks;
        this.navigator = navigator;
        this.failures = failures;
    }

    /** Dữ liệu của cả màn, đọc trong một việc nền (QĐ8). */
    private record DashboardData(OrderDashboard orders, List<LowStockRow> lowStock) {
    }

    /**
     * Một ô số đơn theo trạng thái.
     *
     * @param status         tên trạng thái ({@code PENDING}...), dùng làm bộ lọc và id nút {@code adminCount-{status}}
     * @param label          nhãn trạng thái (BR-005-9)
     * @param countText      số đơn
     * @param lead           {@code true} cho "Chờ xử lý" (viền accent)
     * @param accessibleText "{nhãn trạng thái}: {n} đơn hàng"
     */
    record CountTile(String status, String label, String countText, boolean lead, String accessibleText) {
    }

    /**
     * Một dòng "Sắp hết hàng".
     *
     * @param id             id sản phẩm (mở màn sửa)
     * @param name           tên sản phẩm
     * @param stockText      "Tồn kho: {n}"
     * @param zero           {@code true} khi tồn kho 0 (màn nhấn mạnh bằng chữ, không chỉ bằng màu)
     * @param accessibleText "{tên sản phẩm}, Tồn kho: {n}"
     */
    record LowStockItem(long id, String name, String stockText, boolean zero, String accessibleText) {
    }

    /**
     * Một dòng "Đơn hàng mới nhất", chữ đã định dạng sẵn.
     *
     * @param id             id đơn (mở chi tiết)
     * @param idText         "#{id}"
     * @param dateText       ngày đặt, giờ Việt Nam
     * @param customerName   họ tên khách
     * @param customerEmail  email khách
     * @param totalText      tổng tiền, ví dụ {@code 1.250.000 ₫}
     * @param statusLabel    nhãn trạng thái
     * @param statusKey      khóa chữ thường cho style class ({@code pending}...)
     * @param accessibleText "Đơn hàng #{id}, {ngày đặt}, {khách hàng}, {tổng tiền}, {trạng thái}"
     */
    record LatestOrder(long id, String idText, String dateText, String customerName, String customerEmail,
                       String totalText, String statusLabel, String statusKey, String accessibleText) {
    }

    /** Nạp số liệu ở nền. Gọi lần nữa khi đang nạp thì bị bỏ qua. */
    void load() {
        if (disposed || loading.get()) {
            return;
        }
        loading.set(true);
        pending = tasks.submit(
                () -> new DashboardData(orderAdminService.dashboard(), productAdminService.lowStock()),
                this::show, this::loadFailed);
    }

    private void show(DashboardData data) {
        OrderDashboard orders = data.orders();
        counts.setAll(orders.counts().stream().map(AdminDashboardPresenter::toTile).toList());
        revenueText.set(MONEY.format(orders.deliveredRevenue()));
        List<LatestOrder> latestRows = orders.latest().stream().map(AdminDashboardPresenter::toLatest).toList();
        latest.setAll(latestRows);
        latestEmpty.set(latestRows.isEmpty());
        List<LowStockItem> lowRows = data.lowStock().stream().map(AdminDashboardPresenter::toLowStock).toList();
        lowStock.setAll(lowRows);
        lowStockEmpty.set(lowRows.isEmpty());
        loaded.set(true);
        loading.set(false);
    }

    private void loadFailed(Throwable failure) {
        loading.set(false);
        failures.handle(failure);
    }

    private static CountTile toTile(StatusCount count) {
        String number = Long.toString(count.count());
        return new CountTile(count.status(), count.label(), number, LEAD_STATUS.equals(count.status()),
                count.label() + ": " + number + " đơn hàng");
    }

    private static LowStockItem toLowStock(LowStockRow row) {
        String stockText = "Tồn kho: " + row.stock();
        return new LowStockItem(row.id(), row.name(), stockText, row.stock() == 0, row.name() + ", " + stockText);
    }

    private static LatestOrder toLatest(AdminOrderRow order) {
        String idText = "#" + order.id();
        String date = DATES.format(order.createdAt());
        String total = MONEY.format(order.totalAmount());
        String accessible = "Đơn hàng " + idText + ", " + date + ", " + order.customerName() + ", " + total + ", "
                + order.statusLabel();
        return new LatestOrder(order.id(), idText, date, order.customerName(), order.customerEmail(), total,
                order.statusLabel(), order.statusKey(), accessible);
    }

    // ------------------------------------------------------------------ lối đi

    /** Bấm một ô số: mở "Đơn hàng" đã lọc theo trạng thái đó (AC-006-6). */
    void openStatus(String status) {
        if (!disposed) {
            navigator.navigateTo(AdminOrdersRoute.all().withStatus(status));
        }
    }

    /** "Xem tất cả đơn hàng" (AC-006-8). */
    void openAllOrders() {
        if (!disposed) {
            navigator.navigateTo(AdminOrdersRoute.all());
        }
    }

    /** Bấm một dòng "Sắp hết hàng": mở màn sửa sản phẩm; "Hủy" ở đó về danh sách sản phẩm (AC-006-7). */
    void openProduct(long productId) {
        if (!disposed) {
            navigator.navigateTo(AdminProductFormRoute.edit(productId, AdminProductsRoute.first()));
        }
    }

    /** Bấm một dòng "Đơn hàng mới nhất": mở chi tiết đơn; "Quay lại danh sách đơn" về danh sách đủ đơn. */
    void openOrder(long orderId) {
        if (!disposed) {
            navigator.navigateTo(new AdminOrderDetailRoute(orderId, AdminOrdersRoute.all()));
        }
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

    /** Năm ô số theo thứ tự enum trạng thái (BR-005-9). */
    ObservableList<CountTile> counts() {
        return counts;
    }

    /** "Doanh thu (đơn đã giao)" đã định dạng, ví dụ {@code 0 ₫}. */
    ReadOnlyStringProperty revenueTextProperty() {
        return revenueText;
    }

    /** Sản phẩm sắp hết hàng (đang bán, tồn kho 5 trở xuống, tăng dần, tối đa 10). */
    ObservableList<LowStockItem> lowStock() {
        return lowStock;
    }

    /** {@code true} khi không có sản phẩm nào sắp hết hàng ("Không có sản phẩm nào sắp hết hàng."). */
    ReadOnlyBooleanProperty lowStockEmptyProperty() {
        return lowStockEmpty;
    }

    /** Năm đơn mới nhất. */
    ObservableList<LatestOrder> latest() {
        return latest;
    }

    /** {@code true} khi chưa có đơn nào ("Chưa có đơn hàng nào."). */
    ReadOnlyBooleanProperty latestEmptyProperty() {
        return latestEmpty;
    }
}
