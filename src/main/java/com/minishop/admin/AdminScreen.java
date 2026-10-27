package com.minishop.admin;

import com.minishop.desktop.StatusPill;
import com.minishop.desktop.Tables;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Khung chung của mọi màn trong khu quản trị (QĐ2 của D5): thanh bên trái (thẻ trắng, tiêu đề "Quản trị", bốn
 * {@link ToggleButton} theo {@link AdminSection}) và vùng màn bên phải trong một {@link ScrollPane} dọc. Mọi màn
 * quản trị là một lớp con; thanh trên của cửa sổ chính không đổi (nút "Cửa hàng" ở đó là lối "về cửa hàng").
 *
 * <p><b>Cách dùng cho lớp con (F2 đến F5):</b>
 * <ol>
 *   <li>{@code super(AdminSection.X, nav)} (mục nào của thanh bên được đánh dấu), rồi {@code setId("...Screen")}.</li>
 *   <li>Dựng nội dung (thường bằng {@link #page}) và gọi {@link #setBody(Node)} một lần.</li>
 *   <li>Tự gắn {@code sceneProperty()} để gọi {@code presenter.dispose()} khi {@code newScene == null} (như
 *       {@code OrderHistoryScreen}). Lớp cha chỉ lo thanh bên, cuộn và focus.</li>
 * </ol>
 *
 * <p><b>Đã có sẵn cho lớp con:</b> {@link #bodyScroll()} và {@link #bodyWidthProperty()} (để đổi bố cục hai cột
 * và xếp chồng, ngưỡng gợi ý {@link #TWO_COLUMNS_FROM}); {@link #scrollIntoView(Node)} (tự chạy mỗi khi focus
 * chuyển tới một node nằm ngoài vùng nhìn) và {@link #scrollToTop()}; các hàm tĩnh {@link #pageTitle},
 * {@link #pageHead}, {@link #loadingBox}, {@link #show}, {@link #statusPill}, {@link #makeActivatable} và bộ dựng
 * bảng {@link #fixedColumn}, {@link #growingColumn}, {@link #headerRow}, {@link #dataRow}, {@link #cell},
 * {@link #put}.
 *
 * <p><b>Bảng:</b> {@code GridPane} cột cố định cộng một cột co giãn (như {@code OrderHistoryScreen}), không dùng
 * {@code TableView}; hàng tiêu đề và hàng dữ liệu cùng danh sách cột nên các cột thẳng hàng. Ô chữ xuống dòng
 * ({@code setMinHeight(USE_PREF_SIZE)}) nên tên dài không tràn.
 *
 * <p>Id cố định cho test: {@code adminSidebar}, {@code adminSidebarTitle}, {@code adminNav-dashboard},
 * {@code adminNav-orders}, {@code adminNav-products}, {@code adminNav-categories}. Mục đang mở có
 * {@code selected} (trình đọc màn hình đọc được trạng thái chọn, không chỉ nền ink).
 */
abstract class AdminScreen extends HBox {

    /** Tiêu đề thanh bên và chữ cho trình đọc màn hình của nhóm nút. */
    static final String SIDEBAR_TITLE = "Quản trị";
    /** Chữ cho trình đọc màn hình của vòng quay khi nạp (như màn Thanh toán). */
    static final String LOADING_TEXT = "Đang tải";
    /** Chiều rộng vùng màn (px) từ đó nên xếp hai cột; nhỏ hơn thì xếp chồng. */
    static final int TWO_COLUMNS_FROM = 860;

    private static final double SIDEBAR_WIDTH = 200;
    private static final double FOCUS_MARGIN = 12;

    private final ScrollPane scroll = new ScrollPane();
    private final ChangeListener<Node> focusOwnerListener;

    /**
     * @param current mục của thanh bên được đánh dấu (màn này thuộc mục nào)
     * @param nav     presenter của thanh bên; bấm một mục gọi {@code nav.open(section)}
     */
    protected AdminScreen(AdminSection current, AdminNavPresenter nav) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(nav, "nav");
        getStyleClass().add("admin-layout");
        setFocusTraversable(false);

        VBox sidebar = buildSidebar(current, nav);

        scroll.getStyleClass().add("screen-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        getChildren().setAll(sidebar, scroll);

        focusOwnerListener = (observable, oldOwner, newOwner) -> scrollIntoView(newOwner);
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.focusOwnerProperty().removeListener(focusOwnerListener);
            }
            if (newScene != null) {
                newScene.focusOwnerProperty().addListener(focusOwnerListener);
            }
        });
    }

    private static VBox buildSidebar(AdminSection current, AdminNavPresenter nav) {
        Label title = new Label(SIDEBAR_TITLE);
        title.getStyleClass().add("section-title");

        VBox sidebar = new VBox(title);
        sidebar.setId("adminSidebar");
        sidebar.getStyleClass().add("admin-sidebar");
        sidebar.setAccessibleText(SIDEBAR_TITLE);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        sidebar.setMaxHeight(Region.USE_PREF_SIZE);   // thẻ chỉ cao bằng nội dung, canh ở đầu cột
        title.setId("adminSidebarTitle");

        // Không dùng ToggleGroup: nhóm chỉ cho Tab dừng ở nút đang chọn và phím mũi tên đổi lựa chọn mà không mở
        // màn. Không có nhóm thì mọi mục là một điểm dừng Tab, mũi tên chỉ chuyển focus, và dấu chọn luôn là mục
        // của màn đang mở.
        for (AdminSection section : AdminSection.values()) {
            ToggleButton button = new ToggleButton(section.label());
            button.setId(section.navId());
            button.getStyleClass().addAll("admin-nav-item", "pill");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setAccessibleText(section.label());
            boolean isCurrent = section == current;
            button.setSelected(isCurrent);
            button.setOnAction(event -> {
                // ToggleButton đã tự đổi trạng thái: trả dấu về đúng mục của màn đang mở (màn mới sẽ tự đánh dấu
                // mục của nó); bấm lại mục đang mở không bỏ chọn nó
                button.setSelected(isCurrent);
                nav.open(section);
            });
            // Enter mở mục (JavaFX mặc định chỉ Space)
            button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    button.fire();
                }
            });
            sidebar.getChildren().add(button);
        }
        return sidebar;
    }

    // ------------------------------------------------------------------ vùng màn

    /** Đặt nội dung của màn vào vùng cuộn dọc bên phải. Gọi một lần khi dựng màn. */
    protected final void setBody(Node body) {
        scroll.setContent(Objects.requireNonNull(body, "body"));
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng (sau lần xử lý CSS đầu), có thể sau khi dữ
        // liệu đã về; requestFocus trên node chưa có Scene không làm gì. Báo cho lớp con thử lại đúng lúc đó.
        body.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::onBodyAttached);
            }
        });
    }

    /**
     * Được gọi (trên luồng giao diện, sau một {@code runLater}) khi nội dung của màn vừa vào Scene. Lớp con ghi
     * đè để đặt focus vào chỗ đã chờ từ lúc dữ liệu về; mặc định không làm gì. Phải chịu được việc gọi khi không
     * còn việc chờ.
     */
    protected void onBodyAttached() {
    }

    /** Vùng cuộn dọc chứa nội dung (cho test và cho lớp con cần chỉnh cuộn). */
    protected final ScrollPane bodyScroll() {
        return scroll;
    }

    /** Chiều rộng vùng màn; lớp con nghe nó để đổi giữa hai cột và xếp chồng (bỏ qua giá trị {@code <= 0}). */
    protected final ReadOnlyDoubleProperty bodyWidthProperty() {
        return scroll.widthProperty();
    }

    /** Cuộn về đầu màn (ví dụ sau khi một thao tác nạp lại cả màn và đặt focus vào nút ở đầu). */
    protected final void scrollToTop() {
        scroll.setVvalue(0);
    }

    /**
     * Cuộn dọc đủ để {@code node} (nằm trong nội dung của màn) lọt vào vùng nhìn; đã nhìn thấy hết thì không
     * cuộn. Chạy tự động mỗi khi focus chuyển tới một node, nên sau lỗi form ô lỗi đầu tiên luôn nhìn thấy.
     */
    protected final void scrollIntoView(Node node) {
        Node content = scroll.getContent();
        if (node == null || content == null || !isInside(node, content)) {
            return;
        }
        double viewport = scroll.getViewportBounds().getHeight();
        double total = content.getLayoutBounds().getHeight();
        double scrollable = total - viewport;
        if (viewport <= 0 || scrollable <= 0) {
            return;
        }
        Bounds bounds = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        if (bounds == null) {
            return;
        }
        double top = scroll.getVvalue() * scrollable;
        // Tính cả nhãn của ô (Label.labelFor) để nhãn không bị che dưới mép trên
        double visibleTop = Math.min(bounds.getMinY(), labelTop(node, content));
        if (visibleTop < top) {
            top = visibleTop - FOCUS_MARGIN;
        } else if (bounds.getMaxY() > top + viewport) {
            top = bounds.getMaxY() - viewport + FOCUS_MARGIN;
        } else {
            return;
        }
        scroll.setVvalue(Math.max(0, Math.min(1, top / scrollable)));
    }

    /**
     * Mép trên (trong hệ tọa độ của {@code content}) của nhãn gắn với {@code node} qua {@code labelFor} và nằm
     * cùng cha với nó; không có nhãn thì là mép trên của chính {@code node}.
     */
    private static double labelTop(Node node, Node content) {
        double top = content.sceneToLocal(node.localToScene(node.getBoundsInLocal())).getMinY();
        Parent parent = node.getParent();
        if (parent == null) {
            return top;
        }
        for (Node sibling : parent.getChildrenUnmodifiable()) {
            if (sibling instanceof Label label && label.getLabelFor() == node) {
                Bounds labelBounds = content.sceneToLocal(label.localToScene(label.getBoundsInLocal()));
                if (labelBounds != null) {
                    top = Math.min(top, labelBounds.getMinY());
                }
            }
        }
        return top;
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ khối dựng sẵn

    /** Cột nội dung của một màn ({@code .admin-body}); đưa vào {@link #setBody(Node)}. */
    protected static VBox page(Node... children) {
        VBox page = new VBox(children);
        page.getStyleClass().add("admin-body");
        return page;
    }

    /** Tiêu đề màn ({@code h1}, 32 px) với {@code id} cố định cho test. */
    protected static Label pageTitle(String text, String id) {
        Label title = new Label(text);
        title.setId(id);
        title.getStyleClass().add("page-title");
        title.setAccessibleRole(AccessibleRole.TEXT);
        return title;
    }

    /** Hàng đầu màn: tiêu đề bên trái, các nút ({@code trailing}) bên phải. */
    protected static HBox pageHead(Label title, Node... trailing) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(12, title);
        head.getStyleClass().add("admin-head");
        head.getChildren().add(spacer);
        head.getChildren().addAll(trailing);
        return head;
    }

    /**
     * Hộp vòng quay khi nạp (class {@code .loading}); chỉ hiện khi {@code waiting} đúng. {@code spinnerId} là id
     * của {@link ProgressIndicator} (ví dụ {@code dashboardLoading}).
     */
    protected static HBox loadingBox(String spinnerId, ObservableBooleanValue waiting) {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId(spinnerId);
        indicator.setAccessibleText(LOADING_TEXT);
        HBox box = new HBox(indicator);
        box.getStyleClass().add("loading");
        show(box, waiting);
        show(indicator, waiting);
        return box;
    }

    // ------------------------------------------------------------------ lớp mỏng gọi desktop (D6, QĐ13)
    // Mỗi hàm dưới đây chỉ chuyển một dòng sang desktop.Tables / desktop.StatusPill; logic chỉ có một bản, ở desktop.

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition} ({@link Tables#showWhen}). */
    protected static void show(Node node, ObservableBooleanValue condition) {
        Tables.showWhen(node, condition);
    }

    /** Nhãn trạng thái đơn ({@link StatusPill#create}). */
    protected static Label statusPill(String statusLabel, String statusKey) {
        return StatusPill.create(statusLabel, statusKey);
    }

    /** Biến một node thành điều khiển bấm được ({@link Tables#activatable}). */
    protected static void makeActivatable(Node node, String accessibleText, Runnable action) {
        Tables.activatable(node, accessibleText, action);
    }

    // ------------------------------------------------------------------ bảng (desktop.Tables)

    /** Cột chiều rộng cố định ({@link Tables#fixedColumn}). */
    protected static ColumnConstraints fixedColumn(double width) {
        return Tables.fixedColumn(width);
    }

    /** Cột co giãn ({@link Tables#growingColumn}). */
    protected static ColumnConstraints growingColumn(double minWidth) {
        return Tables.growingColumn(minWidth);
    }

    /** Hàng tiêu đề của bảng, class {@code table-head} và {@code table-th} ({@link Tables#headerRow}). */
    protected static GridPane headerRow(List<ColumnConstraints> columns, String... names) {
        return Tables.headerRow(columns, names);
    }

    /** Hàng dữ liệu rỗng; {@code clickable} thêm {@code table-row-link} ({@link Tables#dataRow}). */
    protected static GridPane dataRow(List<ColumnConstraints> columns, boolean clickable) {
        return Tables.dataRow(columns, clickable);
    }

    /** Ô chữ của bảng ({@link Tables#cell}). */
    protected static Label cell(String text, String styleClass) {
        return Tables.cell(text, styleClass);
    }

    /** Đặt {@code node} vào cột {@code column} của hàng {@code row} ({@link Tables#put}). */
    protected static void put(GridPane row, Node node, int column) {
        Tables.put(row, node, column);
    }
}
