package com.minishop.admin;

import com.minishop.admin.AdminOrdersPresenter.FilterOption;
import com.minishop.admin.AdminOrdersPresenter.OrderRow;
import com.minishop.desktop.PageBar;
import com.minishop.desktop.ScreenFactory;
import java.util.List;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Đơn hàng" của admin (spec 006 AC-006-38, -39, F5 của D5): hàng pill lọc trạng thái ("Tất cả" và năm nhãn),
 * bảng sáu cột "Mã đơn", "Ngày đặt", "Khách hàng" (họ tên và email), "Người nhận", "Tổng tiền", "Trạng thái"
 * (nhãn chữ kèm chấm màu), 20 đơn mỗi trang qua {@link PageBar} (giữ bộ lọc) và hai câu rỗng. Chỉ dựng node và gắn
 * vào {@link AdminOrdersPresenter}; không gọi service.
 *
 * <p><b>Bảng:</b> mỗi hàng là một {@code GridPane} focus được (Tab, Enter, Space hoặc bấm chuột mở chi tiết) có chữ
 * cho trình đọc màn hình "Đơn hàng #{id}, {ngày đặt}, {khách hàng}, {tổng tiền}, {trạng thái}". Cột cố định cộng hai
 * cột co giãn (khách hàng, người nhận) nên bảng vừa vùng màn khoảng 760 px ở cửa sổ 1024 x 700; tên, email và ngày
 * xuống dòng thay vì tràn. Hàng tiêu đề là {@link #headerRow}: hàng dữ liệu có đệm ngang 18 px ở
 * chính {@code GridPane}, nên hàng tiêu đề cũng đệm 18 px ở {@code GridPane} để hai cột co giãn ở giữa vẫn thẳng cột.
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên focus ở hàng đầu (hoặc pill đang chọn khi không có đơn), nên sau khi đổi
 * trang hay bộ lọc người dùng bàn phím ở ngay đầu danh sách mới.
 *
 * <p>Id cố định cho test: {@code adminOrdersScreen}, {@code adminOrdersTitle}, {@code adminOrderFilters},
 * {@code orderFilter-{STATUS|all}}, {@code adminOrderRows} (bảng), {@code adminOrderRow-{id}},
 * {@code adminOrdersEmpty}, {@code adminOrdersEmptyText}, {@code pageBar}, {@code adminOrdersLoading}; cộng các id của
 * thanh bên ({@link AdminScreen}).
 */
final class AdminOrdersScreen extends AdminScreen {

    static final String TITLE = "Đơn hàng";
    static final String FILTER_LABEL = "Lọc theo trạng thái";
    static final String LIST_LABEL = "Danh sách đơn hàng";
    static final String PAGINATION_LABEL = "Phân trang";
    static final String COLUMN_ID = "Mã đơn";
    static final String COLUMN_DATE = "Ngày đặt";
    static final String COLUMN_CUSTOMER = "Khách hàng";
    static final String COLUMN_RECEIVER = "Người nhận";
    static final String COLUMN_TOTAL = "Tổng tiền";
    static final String COLUMN_STATUS = "Trạng thái";

    private static final List<ColumnConstraints> COLUMNS = List.of(
            fixedColumn(60), fixedColumn(104), growingColumn(140), growingColumn(92), fixedColumn(108),
            fixedColumn(124));

    private final AdminOrdersPresenter presenter;

    private final FlowPane pills = new FlowPane();
    private final VBox table = new VBox();
    private final PageBar pageBar = new PageBar(PAGINATION_LABEL);
    private ToggleButton selectedPill;
    private boolean pendingFocus = true;

    AdminOrdersScreen(AdminOrdersPresenter presenter, AdminNavPresenter nav) {
        super(AdminSection.ORDERS, nav);
        this.presenter = presenter;
        setId("adminOrdersScreen");

        HBox head = pageHead(pageTitle(TITLE, "adminOrdersTitle"));

        pills.setId("adminOrderFilters");
        pills.getStyleClass().add("filter-row");
        pills.setAccessibleRole(AccessibleRole.TOOL_BAR);
        pills.setAccessibleText(FILTER_LABEL);
        show(pills, presenter.loadedProperty());

        HBox loadingBox = loadingBox("adminOrdersLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));

        table.setId("adminOrderRows");
        table.getStyleClass().add("order-table");
        table.setAccessibleText(LIST_LABEL);
        VBox tableCard = new VBox(table);
        tableCard.getStyleClass().addAll("summary-card", "admin-table-card");
        show(tableCard, presenter.loadedProperty().and(presenter.emptyProperty().not()));

        VBox emptyState = buildEmptyState();
        show(emptyState, presenter.loadedProperty().and(presenter.emptyProperty()));

        pageBar.setId("pageBar");

        setBody(page(head, pills, loadingBox, tableCard, pageBar, emptyState));

        presenter.filters().addListener((ListChangeListener<FilterOption>) change -> renderPills());
        presenter.rows().addListener((ListChangeListener<OrderRow>) change -> renderRows());
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

        renderPills();
        renderRows();
        renderPages();
    }

    // ------------------------------------------------------------------ dựng node

    private VBox buildEmptyState() {
        Label message = new Label();
        message.setId("adminOrdersEmptyText");
        message.getStyleClass().add("section-title");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);   // nhãn xuống dòng không bị cắt "…" khi hiện lần đầu
        message.textProperty().bind(presenter.emptyTextProperty());
        VBox emptyState = new VBox(message);
        emptyState.setId("adminOrdersEmpty");
        emptyState.getStyleClass().add("empty-state");
        return emptyState;
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    private void renderPills() {
        pills.getChildren().clear();
        selectedPill = null;
        ToggleGroup group = new ToggleGroup();
        // Bấm lại pill đang chọn không được bỏ chọn nó
        group.selectedToggleProperty().addListener((observable, previous, current) -> {
            if (current == null && previous != null) {
                previous.setSelected(true);
            }
        });
        for (FilterOption option : presenter.filters()) {
            ToggleButton pill = new ToggleButton(option.label());
            pill.setId("orderFilter-" + (option.value() == null ? "all" : option.value()));
            pill.getStyleClass().addAll("filter-pill", "pill");
            pill.setToggleGroup(group);
            pill.setSelected(option.selected());
            pill.setAccessibleText(option.label());
            if (option.selected()) {
                selectedPill = pill;
            }
            pill.setOnAction(event -> presenter.filter(option.value()));
            pills.getChildren().add(pill);
        }
    }

    private void renderRows() {
        table.getChildren().clear();
        table.getChildren().add(header());
        for (OrderRow row : presenter.rows()) {
            table.getChildren().add(dataRowNode(row));
        }
        Platform.runLater(this::tryFocus);
    }

    private void renderPages() {
        pageBar.show(presenter.emptyProperty().get() ? null : presenter.pageNavProperty().get(),
                presenter::goToPage);
    }

    /** Hàng tiêu đề dùng chung của khu quản trị ({@link #headerRow}): thẳng cột với hàng dữ liệu. */
    private static GridPane header() {
        return headerRow(COLUMNS, COLUMN_ID, COLUMN_DATE, COLUMN_CUSTOMER, COLUMN_RECEIVER, COLUMN_TOTAL,
                COLUMN_STATUS);
    }

    private GridPane dataRowNode(OrderRow order) {
        GridPane row = dataRow(COLUMNS, true);
        row.setId("adminOrderRow-" + order.id());
        put(row, cell(order.idText(), "text-body"), 0);
        put(row, cell(order.dateText(), "text-body"), 1);

        Label name = cell(order.customerName(), "text-body");
        Label email = cell(order.customerEmail(), "admin-person-meta");
        VBox customer = new VBox(name, email);
        customer.getStyleClass().add("admin-person");
        put(row, customer, 2);

        put(row, cell(order.receiverName(), "text-body"), 3);
        put(row, cell(order.totalText(), "text-body"), 4);
        Label status = statusPill(order.statusLabel(), order.statusKey());
        put(row, status, 5);
        GridPane.setFillWidth(status, false);

        makeActivatable(row, order.accessibleText(), () -> presenter.openOrder(order.id()));
        return row;
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
        Node target = null;
        if (table.getChildren().size() > 1 && !presenter.emptyProperty().get()) {
            target = table.getChildren().get(1);
        } else if (selectedPill != null) {
            target = selectedPill;
        }
        if (target != null && target.getScene() != null) {
            pendingFocus = false;
            target.requestFocus();
        }
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminOrdersRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminOrdersRoute> {

        private final ObjectProvider<AdminOrdersPresenter> presenters;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminOrdersPresenter> presenters, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.nav = nav;
        }

        @Override
        public Class<AdminOrdersRoute> routeType() {
            return AdminOrdersRoute.class;
        }

        @Override
        public Node create(AdminOrdersRoute route) {
            AdminOrdersPresenter presenter = presenters.getObject();
            AdminOrdersScreen screen = new AdminOrdersScreen(presenter, nav);
            presenter.load(route);
            return screen;
        }
    }
}
