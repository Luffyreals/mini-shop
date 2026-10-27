package com.minishop.admin;

import com.minishop.admin.AdminCategoriesPresenter.FocusTarget;
import com.minishop.admin.AdminCategoriesPresenter.Row;
import com.minishop.desktop.ScreenFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.ListChangeListener;
import javafx.collections.SetChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Danh mục" của khu quản trị (spec 006 AC-006-10, -15, -16, F2 của D5): tiêu đề, nút "Thêm danh mục", bảng
 * "Tên", "Mô tả", "Số sản phẩm", "Thao tác" (mỗi dòng có "Sửa" và "Xóa") hoặc "Chưa có danh mục nào.". Chỉ dựng
 * node và gắn vào {@link AdminCategoriesPresenter}; không gọi service. "Xóa" chỉ gọi presenter, hộp xác nhận do
 * presenter mở.
 *
 * <p><b>Bàn phím:</b> Tab đi qua thanh bên, "Thêm danh mục", rồi "Sửa" và "Xóa" của từng dòng; Enter và Space đều
 * bấm nút có focus. Nút có chữ cho trình đọc màn hình "Sửa {tên}", "Xóa {tên}". Nút "Xóa" không bị khóa khi đang
 * xóa (khóa nút đang có focus làm focus nhảy đi); dòng đang xóa có vòng quay nhỏ và presenter bỏ qua lần bấm thứ
 * hai. Sau mỗi lần nạp focus về nút "Xóa" của dòng vừa thao tác nếu dòng còn, không thì nút "Thêm danh mục".
 *
 * <p>Id cố định cho test: {@code adminCategoriesScreen}, {@code categoriesTitle}, {@code addCategory},
 * {@code categoriesLoading}, {@code categoryRows}, {@code categoryRow-{id}}, {@code editCategory-{id}},
 * {@code deleteCategory-{id}}, {@code categoryBusy-{id}}, {@code categoriesEmpty}; cộng các id của thanh bên
 * ({@link AdminScreen}).
 */
final class AdminCategoriesScreen extends AdminScreen {

    static final String TITLE = "Danh mục";
    static final String ADD_TEXT = "Thêm danh mục";
    static final String TABLE_TEXT = "Danh sách danh mục";
    static final String COLUMN_NAME = "Tên";
    static final String COLUMN_DESCRIPTION = "Mô tả";
    static final String COLUMN_COUNT = "Số sản phẩm";
    static final String COLUMN_ACTIONS = "Thao tác";
    static final String EDIT_TEXT = "Sửa";
    static final String DELETE_TEXT = "Xóa";
    static final String EMPTY_TEXT = "Chưa có danh mục nào.";

    private static final double SPINNER_SIZE = 18;
    private static final List<ColumnConstraints> COLUMNS = List.of(
            growingColumn(120), growingColumn(150), fixedColumn(120), fixedColumn(180));

    private final AdminCategoriesPresenter presenter;

    private final Button addButton = new Button(ADD_TEXT);
    private final VBox table = new VBox();
    private final Map<Long, ProgressIndicator> spinners = new HashMap<>();
    private final Map<Long, Button> deleteButtons = new HashMap<>();
    /** Đích focus đã nhận nhưng chưa đặt được vì nội dung chưa vào Scene. */
    private FocusTarget waitingFocus;

    AdminCategoriesScreen(AdminCategoriesPresenter presenter, AdminNavPresenter nav) {
        super(AdminSection.CATEGORIES, nav);
        this.presenter = presenter;
        setId("adminCategoriesScreen");

        addButton.setId("addCategory");
        addButton.getStyleClass().addAll("button-primary", "pill");
        addButton.setOnAction(event -> presenter.add());
        enterFires(addButton);
        HBox head = pageHead(pageTitle(TITLE, "categoriesTitle"), addButton);

        HBox loadingBox = loadingBox("categoriesLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));

        table.setId("categoryRows");
        table.getStyleClass().add("order-table");
        table.setAccessibleText(TABLE_TEXT);

        Label empty = new Label(EMPTY_TEXT);
        empty.setId("categoriesEmpty");
        empty.getStyleClass().addAll("text-body", "text-muted");
        empty.setWrapText(true);
        empty.setMinHeight(Region.USE_PREF_SIZE);

        BooleanBinding hasRows = presenter.loadedProperty().and(presenter.emptyProperty().not());
        BooleanBinding noRows = presenter.loadedProperty().and(presenter.emptyProperty());
        show(table, hasRows);
        show(empty, noRows);

        VBox card = new VBox(table, empty);
        card.getStyleClass().addAll("summary-card", "admin-table-card");
        show(card, presenter.loadedProperty());

        setBody(page(head, loadingBox, card));

        presenter.rows().addListener((ListChangeListener<Row>) change -> renderRows());
        presenter.writingIds().addListener((SetChangeListener<Long>) change -> {
            ProgressIndicator spinner = spinners.get(change.wasAdded() ? change.getElementAdded()
                    : change.getElementRemoved());
            if (spinner != null) {
                spinner.setVisible(change.wasAdded());
            }
        });
        presenter.focusTargetProperty().addListener((observable, oldTarget, target) -> {
            if (target != null) {
                Platform.runLater(() -> applyFocus(target));
            }
        });
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            }
        });

        renderRows();
    }

    // ------------------------------------------------------------------ bảng

    private void renderRows() {
        spinners.clear();
        deleteButtons.clear();
        table.getChildren().clear();
        table.getChildren().add(tableHeader());
        for (Row row : presenter.rows()) {
            table.getChildren().add(rowNode(row));
        }
    }

    /** Hàng tiêu đề dùng chung của khu quản trị ({@link #headerRow}): thẳng cột với hàng dữ liệu. */
    private static GridPane tableHeader() {
        return headerRow(COLUMNS, COLUMN_NAME, COLUMN_DESCRIPTION, COLUMN_COUNT, COLUMN_ACTIONS);
    }

    private GridPane rowNode(Row row) {
        GridPane grid = dataRow(COLUMNS, false);
        grid.setId("categoryRow-" + row.id());

        Label name = cell(row.name(), "text-body");
        name.getStyleClass().add("admin-list-name");
        put(grid, name, 0);

        Label description = cell(row.description(), "text-body");
        description.getStyleClass().add("text-muted");
        put(grid, description, 1);

        put(grid, cell(row.countText(), "text-body"), 2);

        Button edit = new Button(EDIT_TEXT);
        edit.setId("editCategory-" + row.id());
        edit.getStyleClass().addAll("button-secondary", "pill", "admin-compact");
        edit.setAccessibleText(row.editAccessibleText());
        edit.setOnAction(event -> presenter.edit(row.id()));
        enterFires(edit);

        Button delete = new Button(DELETE_TEXT);
        delete.setId("deleteCategory-" + row.id());
        delete.getStyleClass().addAll("button-danger", "pill", "admin-compact");
        delete.setAccessibleText(row.deleteAccessibleText());
        delete.setOnAction(event -> presenter.requestDelete(row));
        enterFires(delete);
        deleteButtons.put(row.id(), delete);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setId("categoryBusy-" + row.id());
        spinner.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        spinner.setMinSize(SPINNER_SIZE, SPINNER_SIZE);
        spinner.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        spinner.setAccessibleText(LOADING_TEXT);
        spinner.setVisible(presenter.writingIds().contains(row.id()));
        spinner.managedProperty().bind(spinner.visibleProperty());
        spinners.put(row.id(), spinner);

        HBox actions = new HBox(edit, delete, spinner);
        actions.getStyleClass().add("admin-actions");
        put(grid, actions, 3);
        return grid;
    }

    // ------------------------------------------------------------------ focus

    private void applyFocus(FocusTarget target) {
        if (getScene() == null) {
            return;
        }
        Node node = null;
        if (target.rowId() != null) {
            node = deleteButtons.get(target.rowId());
        }
        if (node == null) {
            scrollToTop();
            node = addButton;
        }
        if (node.getScene() == null) {
            // Nội dung của ScrollPane chưa vào Scene (skin chưa dựng): chờ onBodyAttached rồi thử lại
            waitingFocus = target;
            return;
        }
        waitingFocus = null;
        node.requestFocus();
    }

    @Override
    protected void onBodyAttached() {
        FocusTarget target = waitingFocus;
        if (target != null) {
            applyFocus(target);
        }
    }

    /** Enter bấm nút có focus (JavaFX mặc định chỉ Space). */
    private static void enterFires(Button button) {
        button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                button.fire();
            }
        });
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminCategoriesRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminCategoriesRoute> {

        private final ObjectProvider<AdminCategoriesPresenter> presenters;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminCategoriesPresenter> presenters, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.nav = nav;
        }

        @Override
        public Class<AdminCategoriesRoute> routeType() {
            return AdminCategoriesRoute.class;
        }

        @Override
        public Node create(AdminCategoriesRoute route) {
            AdminCategoriesPresenter presenter = presenters.getObject();
            AdminCategoriesScreen screen = new AdminCategoriesScreen(presenter, nav);
            presenter.load();
            return screen;
        }
    }
}
