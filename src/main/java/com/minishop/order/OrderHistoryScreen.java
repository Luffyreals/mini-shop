package com.minishop.order;

import com.minishop.desktop.PageBar;
import com.minishop.desktop.ScreenFactory;
import com.minishop.order.OrderHistoryPresenter.OrderRow;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Đơn hàng của tôi" (spec 005, AC-005-20 đến -22, QĐ12 của D4): bảng bốn cột "Mã đơn", "Ngày đặt",
 * "Tổng tiền", "Trạng thái" (nhãn chữ kèm chấm màu), 10 đơn mỗi trang qua {@link PageBar}, trạng thái rỗng "Bạn
 * chưa có đơn hàng nào." với "Bắt đầu mua sắm". Chỉ dựng node và gắn vào {@link OrderHistoryPresenter}; không gọi
 * service.
 *
 * <p><b>Bảng:</b> mỗi hàng là một {@code GridPane} focus được (Tab, Enter hoặc Space hoặc bấm chuột mở chi tiết) có
 * chữ cho trình đọc màn hình "Đơn hàng #{id}, {ngày đặt}, {tổng tiền}, {trạng thái}". Hàng tiêu đề và hàng dữ liệu
 * dùng cùng chiều rộng cột cố định (cột cuối co giãn) nên các cột thẳng hàng; không dùng {@code TableView}.
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên focus ở hàng đầu (hoặc nút "Bắt đầu mua sắm" khi rỗng), nên sau đổi trang
 * người dùng bàn phím ở ngay đầu danh sách mới.
 *
 * <p>Id cố định cho test: {@code ordersScreen}, {@code ordersTitle}, {@code orderRows} (bảng), {@code orderRow-{id}},
 * {@code ordersEmpty}, {@code startShopping}, {@code pageBar}, {@code ordersLoading}.
 */
final class OrderHistoryScreen extends ScrollPane {

    static final String TITLE = "Đơn hàng của tôi";
    static final String COLUMN_ID = "Mã đơn";
    static final String COLUMN_DATE = "Ngày đặt";
    static final String COLUMN_TOTAL = "Tổng tiền";
    static final String COLUMN_STATUS = "Trạng thái";
    static final String EMPTY_TEXT = "Bạn chưa có đơn hàng nào.";
    static final String START_SHOPPING_TEXT = "Bắt đầu mua sắm";
    static final String PAGINATION_LABEL = "Phân trang";

    private static final double ID_WIDTH = 96;
    private static final double DATE_WIDTH = 176;
    private static final double TOTAL_WIDTH = 160;
    private static final double STATUS_MIN_WIDTH = 160;

    private final OrderHistoryPresenter presenter;

    private final VBox table = new VBox();
    private final PageBar pageBar = new PageBar(PAGINATION_LABEL);
    private final Button startShopping = new Button(START_SHOPPING_TEXT);
    private final VBox emptyState = new VBox();
    private final VBox tableBox = new VBox();
    private boolean pendingFocus = true;

    OrderHistoryScreen(OrderHistoryPresenter presenter) {
        this.presenter = presenter;

        setId("ordersScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        Label title = new Label(TITLE);
        title.setId("ordersTitle");
        title.getStyleClass().add("page-title");

        HBox loadingBox = buildLoading();
        buildTable();
        buildEmptyState();

        VBox page = new VBox(title, loadingBox, tableBox, pageBar, emptyState);
        page.getStyleClass().add("page-root");
        setContent(page);

        ObservableBooleanValue hasRows = presenter.loadedProperty().and(presenter.emptyProperty().not());
        show(tableBox, hasRows);
        show(emptyState, presenter.loadedProperty().and(presenter.emptyProperty()));

        presenter.rows().addListener((ListChangeListener<OrderRow>) change -> render());
        presenter.pageNavProperty().addListener((observable, oldNav, newNav) -> renderPages());
        presenter.loadedProperty().addListener((observable, wasLoaded, isLoaded) -> {
            if (isLoaded) {
                Platform.runLater(this::tryFocus);
            }
        });
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            } else {
                Platform.runLater(this::tryFocus);
            }
        });

        render();
        renderPages();
    }

    // ------------------------------------------------------------------ dựng node

    private HBox buildLoading() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("ordersLoading");
        HBox box = new HBox(indicator);
        box.getStyleClass().add("loading");
        ObservableBooleanValue waiting = presenter.loadingProperty().and(presenter.loadedProperty().not());
        show(box, waiting);
        show(indicator, waiting);
        return box;
    }

    private void buildTable() {
        table.setId("orderRows");
        table.getStyleClass().add("order-table");
        table.setAccessibleText(TITLE);
        tableBox.getStyleClass().add("summary-card");
        pageBar.setId("pageBar");
        tableBox.getChildren().setAll(table);
    }

    private void buildEmptyState() {
        Label message = new Label(EMPTY_TEXT);
        message.getStyleClass().add("section-title");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);   // nhãn xuống dòng không bị cắt "…" khi hiện lần đầu
        message.setAlignment(Pos.CENTER);
        startShopping.setId("startShopping");
        startShopping.getStyleClass().addAll("button-primary", "pill");
        startShopping.setOnAction(event -> presenter.startShopping());
        emptyState.setId("ordersEmpty");
        emptyState.getStyleClass().add("empty-state");
        emptyState.getChildren().setAll(message, startShopping);
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    private void render() {
        table.getChildren().clear();
        table.getChildren().add(headerRow());
        for (OrderRow row : presenter.rows()) {
            table.getChildren().add(dataRow(row));
        }
        Platform.runLater(this::tryFocus);
    }

    private void renderPages() {
        pageBar.show(presenter.emptyProperty().get() ? null : presenter.pageNavProperty().get(),
                presenter::goToPage);
    }

    private static GridPane headerRow() {
        GridPane header = baseGrid();
        header.getStyleClass().add("data-table");
        String[] names = {COLUMN_ID, COLUMN_DATE, COLUMN_TOTAL, COLUMN_STATUS};
        for (int column = 0; column < names.length; column++) {
            Label label = new Label(names[column]);
            label.getStyleClass().add("data-table-header");
            header.add(label, column, 0);
        }
        return header;
    }

    private GridPane dataRow(OrderRow row) {
        GridPane grid = baseGrid();
        grid.getStyleClass().addAll("data-table", "data-table-row", "order-row");
        grid.setId("orderRow-" + row.id());
        grid.setFocusTraversable(true);
        grid.setAccessibleRole(AccessibleRole.BUTTON);
        grid.setAccessibleText(row.accessibleText());

        Label id = cell(row.idText(), "text-body");
        Label date = cell(row.dateText(), "text-body");
        Label total = cell(row.totalText(), "text-body");
        grid.add(id, 0, 0);
        grid.add(date, 1, 0);
        grid.add(total, 2, 0);
        Label status = statusPill(row.statusLabel(), row.statusKey());
        grid.add(status, 3, 0);
        GridPane.setHalignment(status, HPos.LEFT);
        GridPane.setFillWidth(status, false);

        Runnable open = () -> presenter.openOrder(row.id());
        grid.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            grid.requestFocus();
            open.run();
        });
        grid.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                event.consume();
                open.run();
            }
        });
        return grid;
    }

    private static Label cell(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static GridPane baseGrid() {
        GridPane grid = new GridPane();
        grid.getColumnConstraints().addAll(fixed(ID_WIDTH), fixed(DATE_WIDTH), fixed(TOTAL_WIDTH), growing());
        return grid;
    }

    private static ColumnConstraints fixed(double width) {
        ColumnConstraints column = new ColumnConstraints(width, width, width);
        column.setHgrow(Priority.NEVER);
        return column;
    }

    private static ColumnConstraints growing() {
        ColumnConstraints column = new ColumnConstraints(STATUS_MIN_WIDTH, STATUS_MIN_WIDTH, Double.MAX_VALUE);
        column.setHgrow(Priority.ALWAYS);
        return column;
    }

    /**
     * Nhãn trạng thái đơn: viên thuốc chữ ink kèm chấm màu; chữ luôn là tên trạng thái nên không chỉ dựa vào màu.
     * Dùng chung với màn chi tiết đơn.
     */
    static Label statusPill(String statusLabel, String statusKey) {
        Label pill = new Label(statusLabel);
        pill.getStyleClass().addAll("status-pill", "status-" + statusKey);
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        pill.setGraphic(dot);
        pill.setAccessibleText(statusLabel);
        return pill;
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ------------------------------------------------------------------ focus

    private void tryFocus() {
        if (!pendingFocus || getScene() == null || !presenter.loadedProperty().get()) {
            return;
        }
        Node target = null;
        if (presenter.emptyProperty().get()) {
            target = startShopping;
        } else if (table.getChildren().size() > 1) {
            target = table.getChildren().get(1);
        }
        if (target != null && target.getScene() != null) {
            pendingFocus = false;
            target.requestFocus();
        }
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link OrdersRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<OrdersRoute> {

        private final ObjectProvider<OrderHistoryPresenter> presenters;

        Factory(ObjectProvider<OrderHistoryPresenter> presenters) {
            this.presenters = presenters;
        }

        @Override
        public Class<OrdersRoute> routeType() {
            return OrdersRoute.class;
        }

        @Override
        public Node create(OrdersRoute route) {
            OrderHistoryPresenter presenter = presenters.getObject();
            OrderHistoryScreen screen = new OrderHistoryScreen(presenter);
            presenter.load(route);
            return screen;
        }
    }
}
