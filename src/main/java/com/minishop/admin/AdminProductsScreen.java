package com.minishop.admin;

import com.minishop.admin.AdminProductsPresenter.ProductRow;
import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.PageBar;
import com.minishop.desktop.ScreenFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.collections.SetChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Sản phẩm" (danh sách, spec 006 AC-006-17, -25, -26, F3 của D5): "Thêm sản phẩm", ô "Tìm theo tên sản phẩm…"
 * (Enter hoặc "Tìm"), bảng "Ảnh", "Tên", "Danh mục", "Giá", "Tồn kho", "Trạng thái", "Thao tác" (20 sản phẩm mỗi
 * trang, cả sản phẩm đã ẩn), {@link PageBar} "Phân trang" và hai câu rỗng. Chỉ dựng node và gắn vào
 * {@link AdminProductsPresenter}; không gọi service.
 *
 * <p><b>Bảng:</b> {@code GridPane} cột cố định cộng cột "Tên" co giãn (không {@code TableView}). Hàng tiêu đề dùng
 * cùng lớp và đệm với hàng dữ liệu ({@code data-table-row}) nên các cột thẳng hàng dù cột co giãn nằm giữa; tổng
 * độ rộng cột cố định {@value #FIXED_WIDTH} px, cột tên rộng tối thiểu {@value #NAME_MIN_WIDTH} px, để bảng vừa
 * vùng màn khoảng 760 px của cửa sổ 1024 x 700. Tên, danh mục và giá xuống dòng. Hai nút của hàng xếp dọc ("Sửa",
 * rồi "Ẩn" hoặc "Hiện lại") vì chiều ngang không đủ cho hai nút cạnh nhau.
 *
 * <p><b>Nút ghi:</b> nút "Ẩn"/"Hiện lại" không bị khóa khi việc của nó chạy (khóa nút đang có focus làm focus nhảy
 * đi); presenter bỏ qua lần bấm thứ hai ({@code writingIds}) và hàng đang ghi có style class {@code is-busy}. Sau
 * khi nạp lại, focus trở về nút cùng loại của cùng hàng (nút "Ẩn" đổi thành "Hiện lại" cùng chỗ); hàng không còn
 * thì focus về "Thêm sản phẩm".
 *
 * <p><b>Focus:</b> sau lần nạp đầu focus ở ô tìm.
 *
 * <p>Id cố định cho test: {@code adminProductsScreen}, {@code productsTitle}, {@code addProduct},
 * {@code productSearchField}, {@code productSearchButton}, {@code productsLoading}, {@code productRows} (bảng),
 * {@code productRow-{id}}, {@code editProduct-{id}}, {@code hideProduct-{id}}, {@code showProduct-{id}},
 * {@code productsEmpty}, {@code pageBar}; cộng các id của thanh bên ({@link AdminScreen}).
 */
final class AdminProductsScreen extends AdminScreen {

    static final String TITLE = "Sản phẩm";
    static final String ADD_PRODUCT = "Thêm sản phẩm";
    static final String SEARCH_PROMPT = "Tìm theo tên sản phẩm…";
    static final String SEARCH_ACCESSIBLE_TEXT = "Tìm theo tên sản phẩm";
    static final String SEARCH_BUTTON = "Tìm";
    static final String TABLE_TITLE = "Danh sách sản phẩm";
    static final String COLUMN_IMAGE = "Ảnh";
    static final String COLUMN_NAME = "Tên";
    static final String COLUMN_CATEGORY = "Danh mục";
    static final String COLUMN_PRICE = "Giá";
    static final String COLUMN_STOCK = "Tồn kho";
    static final String COLUMN_STATUS = "Trạng thái";
    static final String COLUMN_ACTIONS = "Thao tác";
    static final String EDIT = "Sửa";
    static final String PAGINATION_LABEL = "Phân trang";

    /** Ô ảnh nhỏ trong bảng (px). */
    static final int THUMB_WIDTH = 48;
    static final int THUMB_HEIGHT = 60;
    /** Tổng độ rộng các cột cố định (px). */
    static final int FIXED_WIDTH = THUMB_WIDTH + 80 + 92 + 56 + 76 + 104;
    /** Độ rộng tối thiểu của cột "Tên" (px). */
    static final int NAME_MIN_WIDTH = 92;

    private static final int MAX_QUERY_LENGTH = 100;
    private static final String EDIT_PREFIX = "editProduct-";
    private static final String HIDE_PREFIX = "hideProduct-";
    private static final String SHOW_PREFIX = "showProduct-";
    private static final String BUSY_CLASS = "is-busy";

    private static final List<ColumnConstraints> COLUMNS = List.of(
            fixedColumn(THUMB_WIDTH), growingColumn(NAME_MIN_WIDTH), fixedColumn(80), fixedColumn(92),
            fixedColumn(56), fixedColumn(76), fixedColumn(104));

    private final AdminProductsPresenter presenter;
    private final ImageLoader images;

    private final TextField searchField = new TextField();
    private final Button addButton = new Button(ADD_PRODUCT);
    private final VBox table = new VBox();
    private final PageBar pageBar = new PageBar(PAGINATION_LABEL);
    private final Map<Long, GridPane> rowNodes = new HashMap<>();
    private final Map<Long, Button> editButtons = new HashMap<>();
    private final Map<Long, Button> toggleButtons = new HashMap<>();
    private boolean pendingFocus = true;

    AdminProductsScreen(AdminProductsPresenter presenter, AdminNavPresenter nav, ImageLoader images) {
        super(AdminSection.PRODUCTS, nav);
        this.presenter = presenter;
        this.images = images;
        setId("adminProductsScreen");

        addButton.setId("addProduct");
        addButton.getStyleClass().addAll("button-primary", "pill");
        addButton.setOnAction(event -> presenter.add());
        HBox head = pageHead(pageTitle(TITLE, "productsTitle"), addButton);

        HBox toolbar = buildToolbar();
        HBox loadingBox = loadingBox("productsLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));
        VBox tableCard = buildTableCard();
        VBox emptyBox = buildEmptyState();
        pageBar.setId("pageBar");

        setBody(page(head, toolbar, loadingBox, tableCard, pageBar, emptyBox));

        presenter.rows().addListener((ListChangeListener<ProductRow>) change -> render());
        presenter.pageNavProperty().addListener((observable, oldNav, newNav) -> renderPages());
        presenter.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> renderPages());
        presenter.writingIds().addListener((SetChangeListener<Long>) change -> {
            if (change.wasAdded()) {
                markBusy(change.getElementAdded(), true);
            }
            if (change.wasRemoved()) {
                markBusy(change.getElementRemoved(), false);
            }
        });
        presenter.queryTextProperty().addListener((observable, oldText, newText) -> showQuery(newText));
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

        showQuery(presenter.queryTextProperty().get());
        render();
        renderPages();
    }

    // ------------------------------------------------------------------ dựng node

    private HBox buildToolbar() {
        searchField.setId("productSearchField");
        searchField.setPromptText(SEARCH_PROMPT);
        searchField.setAccessibleText(SEARCH_ACCESSIBLE_TEXT);
        searchField.setTextFormatter(new TextFormatter<String>(
                change -> change.getControlNewText().length() <= MAX_QUERY_LENGTH ? change : null));
        searchField.setMaxWidth(420);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(event -> presenter.search(searchField.getText()));

        Button search = new Button(SEARCH_BUTTON);
        search.setId("productSearchButton");
        search.getStyleClass().addAll("button-secondary", "pill");
        search.setOnAction(event -> presenter.search(searchField.getText()));

        HBox toolbar = new HBox(searchField, search);
        toolbar.getStyleClass().add("admin-toolbar");
        return toolbar;
    }

    private VBox buildTableCard() {
        table.setId("productRows");
        table.getStyleClass().add("order-table");
        table.setAccessibleText(TABLE_TITLE);

        VBox card = new VBox(table);
        card.getStyleClass().addAll("summary-card", "admin-table-card");
        show(card, presenter.loadedProperty().and(presenter.emptyProperty().not()));
        return card;
    }

    private VBox buildEmptyState() {
        Label message = new Label();
        message.setId("productsEmpty");
        message.getStyleClass().add("section-title");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);   // nhãn xuống dòng không bị cắt "…" khi hiện lần đầu
        message.textProperty().bind(presenter.emptyTextProperty());

        VBox box = new VBox(message);
        box.getStyleClass().add("empty-state");
        ObservableBooleanValue noResults = presenter.loadedProperty().and(presenter.emptyProperty());
        show(box, noResults);
        show(message, noResults);
        return box;
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    private void render() {
        FocusTarget refocus = captureFocus();
        rowNodes.clear();
        editButtons.clear();
        toggleButtons.clear();
        table.getChildren().clear();
        table.getChildren().add(tableHeader());
        for (ProductRow row : presenter.rows()) {
            table.getChildren().add(productRow(row));
        }
        for (Long id : presenter.writingIds()) {
            markBusy(id, true);
        }
        if (refocus != null) {
            Platform.runLater(() -> restoreFocus(refocus));
        }
    }

    private void renderPages() {
        pageBar.show(presenter.emptyProperty().get() ? null : presenter.pageNavProperty().get(),
                presenter::goToPage);
    }

    /** Hàng tiêu đề dùng chung của khu quản trị ({@link #headerRow}): thẳng cột với hàng dữ liệu. */
    private static GridPane tableHeader() {
        return headerRow(COLUMNS, COLUMN_IMAGE, COLUMN_NAME, COLUMN_CATEGORY, COLUMN_PRICE, COLUMN_STOCK,
                COLUMN_STATUS, COLUMN_ACTIONS);
    }

    private GridPane productRow(ProductRow row) {
        GridPane grid = dataRow(COLUMNS, false);
        grid.setId("productRow-" + row.id());
        rowNodes.put(row.id(), grid);

        ImageTile thumb = new ImageTile(images, THUMB_WIDTH, THUMB_HEIGHT);
        thumb.getStyleClass().add("admin-thumb");
        // Chưa có ảnh: ImageTile đọc "Chưa có ảnh"; có ảnh: đọc tên sản phẩm
        thumb.show(row.imageUrl(), row.imageUrl() == null ? null : row.name());
        put(grid, thumb, 0);

        Label name = cell(row.name(), "text-body");
        name.getStyleClass().add("admin-list-name");
        put(grid, name, 1);
        put(grid, cell(row.categoryName(), "text-body"), 2);
        put(grid, cell(row.priceText(), "text-body"), 3);
        put(grid, cell(row.stockText(), "text-body"), 4);
        put(grid, flag(row), 5);
        put(grid, actions(row), 6);
        return grid;
    }

    private static Label flag(ProductRow row) {
        Label flag = new Label(row.flagText());
        flag.getStyleClass().add(row.active() ? "admin-flag-on" : "admin-flag-off");
        Region dot = new Region();
        dot.getStyleClass().add("stock-dot");
        flag.setGraphic(dot);
        flag.setMinWidth(Region.USE_PREF_SIZE);
        flag.setAccessibleText(row.flagText());
        return flag;
    }

    private VBox actions(ProductRow row) {
        long id = row.id();
        Button edit = actionButton(EDIT, EDIT_PREFIX + id, row.editAccessibleText());
        edit.setOnAction(event -> presenter.edit(id));
        editButtons.put(id, edit);

        Button toggle = actionButton(row.toggleLabel(), (row.active() ? HIDE_PREFIX : SHOW_PREFIX) + id,
                row.toggleAccessibleText());
        toggle.setOnAction(event -> {
            if (row.active()) {
                presenter.hide(id);
            } else {
                presenter.show(id);
            }
        });
        toggleButtons.put(id, toggle);

        VBox box = new VBox(edit, toggle);
        box.getStyleClass().add("admin-actions");
        return box;
    }

    private static Button actionButton(String text, String id, String accessibleText) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().addAll("button-secondary", "pill", "admin-compact");
        button.setAccessibleText(accessibleText);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** Hàng đang ghi có class {@code is-busy} (nút không bị khóa: xem mô tả lớp). */
    private void markBusy(Long productId, boolean busy) {
        GridPane row = rowNodes.get(productId);
        if (row == null) {
            return;
        }
        if (busy) {
            if (!row.getStyleClass().contains(BUSY_CLASS)) {
                row.getStyleClass().add(BUSY_CLASS);
            }
        } else {
            row.getStyleClass().remove(BUSY_CLASS);
        }
    }

    private void showQuery(String text) {
        // Chỉ điền khi người dùng chưa gõ gì: không bao giờ ghi đè chữ đang gõ
        if (text != null && !text.isEmpty() && searchField.getText().isEmpty()) {
            searchField.setText(text);
            searchField.positionCaret(text.length());
        }
    }

    // ------------------------------------------------------------------ focus

    /** Nút nào của hàng nào đang có focus trong bảng (để trả focus sau khi bảng dựng lại). */
    private record FocusTarget(long productId, boolean editButton) {
    }

    private FocusTarget captureFocus() {
        Scene scene = getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        if (owner == null || owner.getId() == null || !isInside(owner, table)) {
            return null;
        }
        String id = owner.getId();
        try {
            if (id.startsWith(EDIT_PREFIX)) {
                return new FocusTarget(Long.parseLong(id.substring(EDIT_PREFIX.length())), true);
            }
            if (id.startsWith(HIDE_PREFIX)) {
                return new FocusTarget(Long.parseLong(id.substring(HIDE_PREFIX.length())), false);
            }
            if (id.startsWith(SHOW_PREFIX)) {
                return new FocusTarget(Long.parseLong(id.substring(SHOW_PREFIX.length())), false);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private void restoreFocus(FocusTarget target) {
        if (getScene() == null) {
            return;
        }
        Button button = target.editButton() ? editButtons.get(target.productId())
                : toggleButtons.get(target.productId());
        // Hàng không còn (đã bị xóa hoặc sang trang khác): về nút đầu màn
        (button != null ? button : addButton).requestFocus();
    }

    @Override
    protected void onBodyAttached() {
        tryFocus();
    }

    private void tryFocus() {
        if (!pendingFocus || getScene() == null || !presenter.loadedProperty().get()) {
            return;
        }
        if (searchField.getScene() != null) {
            pendingFocus = false;
            searchField.requestFocus();
        }
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminProductsRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminProductsRoute> {

        private final ObjectProvider<AdminProductsPresenter> presenters;
        private final AdminNavPresenter nav;
        private final ImageLoader images;

        Factory(ObjectProvider<AdminProductsPresenter> presenters, AdminNavPresenter nav, ImageLoader images) {
            this.presenters = presenters;
            this.nav = nav;
            this.images = images;
        }

        @Override
        public Class<AdminProductsRoute> routeType() {
            return AdminProductsRoute.class;
        }

        @Override
        public Node create(AdminProductsRoute route) {
            AdminProductsPresenter presenter = presenters.getObject();
            AdminProductsScreen screen = new AdminProductsScreen(presenter, nav, images);
            presenter.load(route);
            return screen;
        }
    }
}
