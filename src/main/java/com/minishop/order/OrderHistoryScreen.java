package com.minishop.order;

import com.minishop.desktop.LoadError;
import com.minishop.desktop.PageBar;
import com.minishop.desktop.ScreenFactory;
import com.minishop.desktop.StatusPill;
import com.minishop.desktop.Tables;
import com.minishop.order.OrderHistoryPresenter.OrderRow;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
 * <p><b>Bảng:</b> dựng bằng {@link Tables} (D6, QĐ13): mỗi hàng là một {@code GridPane} focus được (Tab, Enter hoặc
 * Space hoặc bấm chuột mở chi tiết) có chữ cho trình đọc màn hình "Đơn hàng #{id}, {ngày đặt}, {tổng tiền},
 * {trạng thái}". Hàng tiêu đề và hàng dữ liệu dùng cùng danh sách cột (cột cuối co giãn) nên các cột thẳng hàng;
 * không dùng {@code TableView}.
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên focus ở hàng đầu (hoặc nút "Bắt đầu mua sắm" khi rỗng), nên sau đổi trang
 * người dùng bàn phím ở ngay đầu danh sách mới. Khi lần nạp đầu lỗi (QĐ12) focus ở "Thử lại", và sau khi thử lại
 * thành công về hàng đầu như trên.
 *
 * <p>Id cố định cho test: {@code ordersScreen}, {@code ordersTitle}, {@code orderRows} (bảng), {@code orderRow-{id}},
 * {@code ordersEmpty}, {@code startShopping}, {@code pageBar}, {@code ordersLoading}, {@code ordersLoadError},
 * {@code ordersRetry}.
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

    private static final List<ColumnConstraints> COLUMNS = List.of(Tables.fixedColumn(ID_WIDTH),
            Tables.fixedColumn(DATE_WIDTH), Tables.fixedColumn(TOTAL_WIDTH), Tables.growingColumn(STATUS_MIN_WIDTH));

    private final OrderHistoryPresenter presenter;

    private final VBox table = new VBox();
    private final PageBar pageBar = new PageBar(PAGINATION_LABEL);
    private final Button startShopping = new Button(START_SHOPPING_TEXT);
    private final VBox emptyState = new VBox();
    private final VBox tableBox = new VBox();
    private final VBox loadError;
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

        loadError = LoadError.create("orders", presenter::retry);

        VBox page = new VBox(title, loadingBox, loadError, tableBox, pageBar, emptyState);
        page.getStyleClass().add("page-root");
        setContent(page);
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng (ở pulse đầu sau khi màn được gắn). Lỗi nạp đến
        // trước lúc đó thì tryFocus thấy "Thử lại" chưa có Scene và bỏ qua, nên thử lại khi trang vào Scene
        page.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::tryFocus);
            }
        });

        ObservableBooleanValue hasRows = presenter.loadedProperty().and(presenter.emptyProperty().not());
        Tables.showWhen(tableBox, hasRows);
        Tables.showWhen(emptyState, presenter.loadedProperty().and(presenter.emptyProperty()));
        Tables.showWhen(loadError, presenter.loadFailedProperty());

        presenter.rows().addListener((ListChangeListener<OrderRow>) change -> render());
        presenter.pageNavProperty().addListener((observable, oldNav, newNav) -> renderPages());
        presenter.loadedProperty().addListener((observable, wasLoaded, isLoaded) -> {
            if (isLoaded) {
                Platform.runLater(this::tryFocus);
            }
        });
        presenter.loadFailedProperty().addListener((observable, wasFailed, isFailed) -> {
            if (isFailed) {
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
        Tables.showWhen(box, waiting);
        Tables.showWhen(indicator, waiting);
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
        return Tables.headerRow(COLUMNS, COLUMN_ID, COLUMN_DATE, COLUMN_TOTAL, COLUMN_STATUS);
    }

    private GridPane dataRow(OrderRow row) {
        GridPane grid = Tables.dataRow(COLUMNS, true);
        grid.setId("orderRow-" + row.id());
        Tables.put(grid, Tables.cell(row.idText(), "text-body"), 0);
        Tables.put(grid, Tables.cell(row.dateText(), "text-body"), 1);
        Tables.put(grid, Tables.cell(row.totalText(), "text-body"), 2);
        Tables.put(grid, StatusPill.create(row.statusLabel(), row.statusKey()), 3);
        Tables.activatable(grid, row.accessibleText(), () -> presenter.openOrder(row.id()));
        return grid;
    }

    // ------------------------------------------------------------------ focus

    private void tryFocus() {
        if (!pendingFocus || getScene() == null) {
            return;
        }
        if (presenter.loadFailedProperty().get()) {
            // Khối lỗi: focus ở "Thử lại"; pendingFocus còn đó để khi thử lại thành công focus về hàng đầu
            Button retry = LoadError.retryButton(loadError);
            if (retry.getScene() != null && !searchFieldFocused()) {
                retry.requestFocus();
            }
            return;
        }
        if (!presenter.loadedProperty().get()) {
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

    /** Người dùng đang gõ ở ô tìm kiếm trên thanh trên: không giành focus của họ. */
    private boolean searchFieldFocused() {
        Node owner = getScene().getFocusOwner();
        return owner != null && "searchField".equals(owner.getId());
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
