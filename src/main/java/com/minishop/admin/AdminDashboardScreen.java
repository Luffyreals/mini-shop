package com.minishop.admin;

import com.minishop.admin.AdminDashboardPresenter.CountTile;
import com.minishop.admin.AdminDashboardPresenter.LatestOrder;
import com.minishop.admin.AdminDashboardPresenter.LowStockItem;
import com.minishop.desktop.ScreenFactory;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Tổng quan" (spec 006 AC-006-6 đến -8, F1 của D5): năm ô số đơn theo trạng thái (mỗi ô là một điều khiển
 * focus được, mở "Đơn hàng" đã lọc), "Doanh thu (đơn đã giao)", "Đơn hàng mới nhất" (bảng 5 cột, hàng bấm được) và
 * "Sắp hết hàng" (danh sách bấm được, mở màn sửa sản phẩm), kèm hai câu rỗng. Chỉ dựng node và gắn vào
 * {@link AdminDashboardPresenter}; không gọi service.
 *
 * <p><b>Bố cục:</b> từ {@value #TWO_COLUMNS_FROM_WIDTH} px bề rộng vùng màn thì hai cột (đơn mới nhất bên trái;
 * doanh thu và sắp hết hàng bên phải, rộng {@value #SIDE_WIDTH} px), nhỏ hơn thì xếp chồng. Ngưỡng cao hơn
 * {@link #TWO_COLUMNS_FROM} vì bảng năm cột cần khoảng 700 px mới đủ chỗ.
 *
 * <p><b>Focus:</b> sau lần nạp đầu focus ở ô "Chờ xử lý" (ô số đầu tiên).
 *
 * <p>Id cố định cho test: {@code adminDashboardScreen}, {@code dashboardTitle}, {@code viewAllOrders},
 * {@code dashboardLoading}, {@code adminCount-{STATUS}}, {@code revenueValue}, {@code latestOrders},
 * {@code latestOrder-{id}}, {@code latestOrdersEmpty}, {@code lowStockList}, {@code lowStock-{id}},
 * {@code lowStockEmpty}; cộng các id của thanh bên ({@link AdminScreen}).
 */
final class AdminDashboardScreen extends AdminScreen {

    static final String TITLE = "Tổng quan";
    static final String VIEW_ALL_ORDERS = "Xem tất cả đơn hàng";
    static final String REVENUE_LABEL = "Doanh thu (đơn đã giao)";
    static final String LATEST_TITLE = "Đơn hàng mới nhất";
    static final String LATEST_EMPTY = "Chưa có đơn hàng nào.";
    static final String LOW_STOCK_TITLE = "Sắp hết hàng";
    static final String LOW_STOCK_EMPTY = "Không có sản phẩm nào sắp hết hàng.";
    static final String COLUMN_ID = "Mã đơn";
    static final String COLUMN_DATE = "Ngày đặt";
    static final String COLUMN_CUSTOMER = "Khách hàng";
    static final String COLUMN_TOTAL = "Tổng tiền";
    static final String COLUMN_STATUS = "Trạng thái";

    static final int TWO_COLUMNS_FROM_WIDTH = 1080;
    static final int SIDE_WIDTH = 300;

    private static final List<ColumnConstraints> LATEST_COLUMNS = List.of(
            fixedColumn(64), fixedColumn(136), growingColumn(124), fixedColumn(112), fixedColumn(132));

    private final AdminDashboardPresenter presenter;

    private final GridPane counts = new GridPane();
    private final VBox latestTable = new VBox();
    private final VBox lowStockList = new VBox();
    private final GridPane columns = new GridPane();
    private final VBox latestCard = new VBox();
    private final VBox sideColumn = new VBox();
    private boolean stacked;
    private boolean pendingFocus = true;

    AdminDashboardScreen(AdminDashboardPresenter presenter, AdminNavPresenter nav) {
        super(AdminSection.DASHBOARD, nav);
        this.presenter = presenter;
        setId("adminDashboardScreen");

        Button viewAll = new Button(VIEW_ALL_ORDERS);
        viewAll.setId("viewAllOrders");
        viewAll.getStyleClass().addAll("button-primary", "pill");
        viewAll.setOnAction(event -> presenter.openAllOrders());
        HBox head = pageHead(pageTitle(TITLE, "dashboardTitle"), viewAll);

        ObservableBooleanValue ready = presenter.loadedProperty();
        HBox loadingBox = loadingBox("dashboardLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));

        buildCounts();
        buildLatestCard();
        buildSideColumn();
        buildColumns();
        show(counts, ready);
        show(columns, ready);

        setBody(page(head, loadingBox, counts, columns));

        presenter.counts().addListener((ListChangeListener<CountTile>) change -> renderCounts());
        presenter.latest().addListener((ListChangeListener<LatestOrder>) change -> renderLatest());
        presenter.lowStock().addListener((ListChangeListener<LowStockItem>) change -> renderLowStock());
        presenter.loadedProperty().addListener((observable, wasLoaded, isLoaded) -> {
            if (isLoaded) {
                Platform.runLater(this::tryFocus);
            }
        });
        bodyWidthProperty().addListener((observable, oldWidth, newWidth) -> updateLayout(newWidth.doubleValue()));
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            } else {
                Platform.runLater(this::tryFocus);
            }
        });

        renderCounts();
        renderLatest();
        renderLowStock();
    }

    // ------------------------------------------------------------------ dựng node

    private void buildCounts() {
        counts.setId("adminCounts");
        counts.getStyleClass().add("admin-counts");
        for (int i = 0; i < 5; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(20);
            column.setHgrow(Priority.ALWAYS);
            counts.getColumnConstraints().add(column);
        }
    }

    private void buildLatestCard() {
        Label title = new Label(LATEST_TITLE);
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);

        latestTable.setId("latestOrders");
        latestTable.getStyleClass().add("order-table");
        latestTable.setAccessibleText(LATEST_TITLE);

        Label empty = new Label(LATEST_EMPTY);
        empty.setId("latestOrdersEmpty");
        empty.getStyleClass().addAll("text-body", "text-muted");
        empty.setWrapText(true);
        empty.setMinHeight(Region.USE_PREF_SIZE);

        show(latestTable, presenter.latestEmptyProperty().not());
        show(empty, presenter.latestEmptyProperty());

        latestCard.getStyleClass().add("summary-card");
        latestCard.getChildren().setAll(title, latestTable, empty);
    }

    private void buildSideColumn() {
        Label revenueCaption = new Label(REVENUE_LABEL);
        revenueCaption.getStyleClass().addAll("text-body", "text-muted");
        revenueCaption.setWrapText(true);
        revenueCaption.setMinHeight(Region.USE_PREF_SIZE);

        Label revenueValue = new Label();
        revenueValue.setId("revenueValue");
        revenueValue.getStyleClass().add("text-h1");
        revenueValue.setWrapText(true);
        revenueValue.setMinHeight(Region.USE_PREF_SIZE);
        revenueValue.textProperty().bind(presenter.revenueTextProperty());
        revenueValue.accessibleTextProperty().bind(
                Bindings.concat(REVENUE_LABEL + ": ", presenter.revenueTextProperty()));

        VBox revenue = new VBox(revenueCaption, revenueValue);
        revenue.getStyleClass().addAll("dark-block", "admin-revenue");

        Label title = new Label(LOW_STOCK_TITLE);
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);

        lowStockList.setId("lowStockList");
        lowStockList.getStyleClass().add("admin-list");
        lowStockList.setAccessibleText(LOW_STOCK_TITLE);

        Label empty = new Label(LOW_STOCK_EMPTY);
        empty.setId("lowStockEmpty");
        empty.getStyleClass().addAll("text-body", "text-muted");
        empty.setWrapText(true);
        empty.setMinHeight(Region.USE_PREF_SIZE);

        show(lowStockList, presenter.lowStockEmptyProperty().not());
        show(empty, presenter.lowStockEmptyProperty());

        VBox lowStockCard = new VBox(title, lowStockList, empty);
        lowStockCard.getStyleClass().add("summary-card");

        sideColumn.getStyleClass().add("admin-side-column");
        sideColumn.getChildren().setAll(revenue, lowStockCard);
    }

    private void buildColumns() {
        columns.getStyleClass().add("admin-columns");
        columns.getChildren().addAll(latestCard, sideColumn);
        place(false);
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    private void renderCounts() {
        counts.getChildren().clear();
        int column = 0;
        for (CountTile tile : presenter.counts()) {
            counts.add(countNode(tile), column++, 0);
        }
    }

    private Node countNode(CountTile tile) {
        Label label = new Label(tile.label());
        label.getStyleClass().addAll("text-body", "admin-count-label");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);

        Label value = new Label(tile.countText());
        value.getStyleClass().add("admin-count-value");

        VBox box = new VBox(label, value);
        box.setId("adminCount-" + tile.status());
        box.getStyleClass().add("admin-count");
        if (tile.lead()) {
            box.getStyleClass().add("admin-count-lead");
        }
        box.setMaxWidth(Double.MAX_VALUE);
        makeActivatable(box, tile.accessibleText(), () -> presenter.openStatus(tile.status()));
        GridPane.setFillWidth(box, true);
        GridPane.setValignment(box, VPos.TOP);
        return box;
    }

    private void renderLatest() {
        latestTable.getChildren().clear();
        latestTable.getChildren().add(headerRow(LATEST_COLUMNS,
                COLUMN_ID, COLUMN_DATE, COLUMN_CUSTOMER, COLUMN_TOTAL, COLUMN_STATUS));
        for (LatestOrder order : presenter.latest()) {
            latestTable.getChildren().add(latestRow(order));
        }
    }

    private GridPane latestRow(LatestOrder order) {
        GridPane row = dataRow(LATEST_COLUMNS, true);
        row.setId("latestOrder-" + order.id());
        put(row, cell(order.idText(), "text-body"), 0);
        put(row, cell(order.dateText(), "text-body"), 1);

        Label name = cell(order.customerName(), "text-body");
        Label email = cell(order.customerEmail(), "admin-person-meta");
        VBox customer = new VBox(name, email);
        customer.getStyleClass().add("admin-person");
        put(row, customer, 2);

        put(row, cell(order.totalText(), "text-body"), 3);
        Label status = statusPill(order.statusLabel(), order.statusKey());
        put(row, status, 4);
        GridPane.setFillWidth(status, false);

        makeActivatable(row, order.accessibleText(), () -> presenter.openOrder(order.id()));
        return row;
    }

    private void renderLowStock() {
        lowStockList.getChildren().clear();
        for (LowStockItem item : presenter.lowStock()) {
            lowStockList.getChildren().add(lowStockNode(item));
        }
    }

    private Node lowStockNode(LowStockItem item) {
        Label name = new Label(item.name());
        name.getStyleClass().addAll("text-body", "admin-list-name");
        name.setWrapText(true);
        name.setMinHeight(Region.USE_PREF_SIZE);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label stock = new Label(item.stockText());
        stock.getStyleClass().addAll("stock-label", item.zero() ? "stock-out" : "admin-stock-low");
        Region dot = new Region();
        dot.getStyleClass().add("stock-dot");
        stock.setGraphic(dot);
        stock.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, name, stock);
        row.setId("lowStock-" + item.id());
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("admin-list-row");
        makeActivatable(row, item.accessibleText(), () -> presenter.openProduct(item.id()));
        return row;
    }

    // ------------------------------------------------------------------ bố cục 2 cột hoặc xếp chồng

    private void updateLayout(double width) {
        if (width <= 0) {
            return;
        }
        boolean wantStacked = width < TWO_COLUMNS_FROM_WIDTH;
        if (wantStacked != stacked) {
            place(wantStacked);
        }
    }

    private void place(boolean stack) {
        stacked = stack;
        columns.getColumnConstraints().clear();
        ColumnConstraints main = new ColumnConstraints(0, 0, Double.MAX_VALUE);
        main.setHgrow(Priority.ALWAYS);
        columns.getColumnConstraints().add(main);
        if (!stack) {
            ColumnConstraints side = new ColumnConstraints(SIDE_WIDTH, SIDE_WIDTH, SIDE_WIDTH);
            side.setHgrow(Priority.NEVER);
            columns.getColumnConstraints().add(side);
        }
        GridPane.setColumnIndex(latestCard, 0);
        GridPane.setRowIndex(latestCard, 0);
        GridPane.setValignment(latestCard, VPos.TOP);
        GridPane.setFillWidth(latestCard, true);
        GridPane.setColumnIndex(sideColumn, stack ? 0 : 1);
        GridPane.setRowIndex(sideColumn, stack ? 1 : 0);
        GridPane.setValignment(sideColumn, VPos.TOP);
        GridPane.setFillWidth(sideColumn, true);
    }

    // ------------------------------------------------------------------ focus

    @Override
    protected void onBodyAttached() {
        tryFocus();
    }

    private void tryFocus() {
        if (!pendingFocus || getScene() == null || !presenter.loadedProperty().get()) {
            return;
        }
        Node target = counts.getChildren().isEmpty() ? null : counts.getChildren().get(0);
        if (target != null && target.getScene() != null) {
            pendingFocus = false;
            target.requestFocus();
        }
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminDashboardRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminDashboardRoute> {

        private final ObjectProvider<AdminDashboardPresenter> presenters;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminDashboardPresenter> presenters, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.nav = nav;
        }

        @Override
        public Class<AdminDashboardRoute> routeType() {
            return AdminDashboardRoute.class;
        }

        @Override
        public Node create(AdminDashboardRoute route) {
            AdminDashboardPresenter presenter = presenters.getObject();
            AdminDashboardScreen screen = new AdminDashboardScreen(presenter, nav);
            presenter.load();
            return screen;
        }
    }
}
