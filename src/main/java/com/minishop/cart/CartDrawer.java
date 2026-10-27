package com.minishop.cart;

import com.minishop.desktop.DrawerHost;
import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.ShellContributor;
import com.minishop.desktop.ShellPresenter;
import com.minishop.desktop.ShellPresenter.ShellAction;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.SetChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Nội dung drawer "Giỏ hàng" (AC-004-9 đến -14, AC-005-1; QĐ1, QĐ6 của D4): đầu drawer có tiêu đề và nút "Đóng";
 * thân cuộn dọc có mỗi sản phẩm một dòng; chân có "Tổng cộng" và "Đặt hàng". Giỏ trống thì thân và chân được thay
 * bằng "Giỏ hàng của bạn đang trống." và "Tiếp tục mua sắm". Chỉ dựng node và gắn vào {@link CartDrawerPresenter};
 * không gọi service. {@link DrawerHost} lo khung (lớp phủ, Esc, giữ focus).
 *
 * <p><b>Dòng giỏ:</b> ảnh nhỏ, tên (nút mở chi tiết; dòng ngừng bán chỉ là chữ), đơn giá và "Xóa", nhãn dòng, rồi
 * − / ô số / + và thành tiền. Dòng không mua được (ngừng bán, hết hàng) chỉ hiện số lượng bằng chữ. Ô số lượng nhận
 * chữ thô, Enter gửi; rời ô mà không Enter thì ô trả về số hiện tại. Trong lúc một dòng đang ghi, dòng đó chỉ mờ đi
 * (điều khiển không bị khóa, nên Enter lặp lại không làm focus nhảy đi; presenter bỏ qua thao tác chồng); sau khi
 * nạp lại, focus quay về đúng điều khiển vừa dùng nếu dòng còn, nếu không thì về nút "Đóng".
 *
 * <p>Id cố định cho test: {@code cartDrawer}, {@code cartDrawerTitle}, {@code cartDrawerClose}, {@code cartLines},
 * {@code cartLine-{id}}, {@code lineLink-{id}}, {@code lineLabel-{id}}, {@code qtyMinus-{id}},
 * {@code qtyField-{id}}, {@code qtyPlus-{id}}, {@code removeLine-{id}}, {@code lineTotal-{id}}, {@code cartTotal},
 * {@code checkoutButton}, {@code cartNotOrderable}, {@code cartEmpty}, {@code continueShopping},
 * {@code cartLoading}.
 */
final class CartDrawer extends VBox {

    static final String TITLE = "Giỏ hàng";
    static final String CLOSE_TEXT = "Đóng";
    static final String LINES_ACCESSIBLE_TEXT = "Sản phẩm trong giỏ hàng";
    static final String TOTAL_LABEL = "Tổng cộng";
    static final String CHECKOUT_TEXT = "Đặt hàng";
    static final String CONTINUE_TEXT = "Tiếp tục mua sắm";
    static final String REMOVE_TEXT = "Xóa";
    static final String QUANTITY_LABEL = "Số lượng";
    private static final String MINUS_GLYPH = "−";
    private static final String PLUS_GLYPH = "+";
    private static final double IMAGE_WIDTH = 72;
    private static final double IMAGE_HEIGHT = 90;
    private static final String WRITING_CLASS = "cart-line-writing";

    /** Điều khiển của dòng mà người dùng vừa dùng, để trả focus sau khi nạp lại. */
    private enum Control { MINUS, FIELD, PLUS, REMOVE }

    private record FocusTarget(long productId, Control control) {
    }

    /** Các điều khiển của một dòng đang hiện, để khóa/mở khóa mà không dựng lại. */
    private record RowControls(HBox row, Button name, Button minus, TextField field, Button plus, Button remove,
                               CartLineView line) {
    }

    private final CartDrawerPresenter presenter;
    private final ImageLoader images;

    private final Button close = new Button(CLOSE_TEXT);
    private final VBox lineBox = new VBox();
    private final Map<Long, RowControls> rows = new HashMap<>();
    private FocusTarget pendingFocus;

    CartDrawer(CartDrawerPresenter presenter, ImageLoader images) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.images = Objects.requireNonNull(images, "images");

        setId("cartDrawer");
        setAccessibleText(TITLE);

        getChildren().addAll(buildHeader(), buildBody(), buildFooter());

        presenter.lines().addListener((ListChangeListener<CartLineView>) change -> rebuild());
        presenter.writingIds().addListener((SetChangeListener<Long>) change -> {
            refreshLocks();
            if (change.wasRemoved() && pendingFocus != null) {
                // Dòng vừa được mở khóa (sau khi nạp lại, hoặc ghi lỗi): đợi một nhịp cho dòng mới vào Scene
                Platform.runLater(this::restoreFocus);
            }
        });
        rebuild();
    }

    /** Đưa focus vào nút "Đóng" (khi mở drawer). */
    void focusClose() {
        close.requestFocus();
    }

    // ------------------------------------------------------------------ đầu, thân, chân

    private Node buildHeader() {
        Label title = new Label(TITLE);
        title.setId("cartDrawerTitle");
        title.getStyleClass().add("text-h2");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        close.setId("cartDrawerClose");
        close.getStyleClass().addAll("button-secondary", "pill");
        close.setOnAction(event -> presenter.close());

        HBox header = new HBox(title, close);
        header.getStyleClass().add("drawer-header");
        return header;
    }

    private Node buildBody() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("cartLoading");
        HBox loadingBox = new HBox(indicator);
        loadingBox.getStyleClass().add("loading");
        show(loadingBox, presenter.loadingProperty().and(presenter.loadedProperty().not()));

        Label emptyText = new Label(CartDrawerPresenter.MSG_EMPTY);
        emptyText.getStyleClass().add("text-body");
        emptyText.setWrapText(true);
        emptyText.setMinHeight(Region.USE_PREF_SIZE);
        Button continueShopping = new Button(CONTINUE_TEXT);
        continueShopping.setId("continueShopping");
        continueShopping.getStyleClass().addAll("button-primary", "pill");
        continueShopping.setOnAction(event -> presenter.continueShopping());
        VBox emptyState = new VBox(emptyText, continueShopping);
        emptyState.setId("cartEmpty");
        emptyState.getStyleClass().add("empty-state");
        show(emptyState, presenter.loadedProperty().and(presenter.emptyProperty()));

        lineBox.setId("cartLines");
        lineBox.getStyleClass().add("cart-lines");
        lineBox.setAccessibleText(LINES_ACCESSIBLE_TEXT);
        ScrollPane scroll = new ScrollPane(lineBox);
        scroll.setId("cartLinesScroll");
        scroll.getStyleClass().add("screen-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        show(scroll, presenter.loadedProperty().and(presenter.emptyProperty().not()));

        StackPane body = new StackPane(loadingBox, emptyState, scroll);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private Node buildFooter() {
        Label totalLabel = new Label(TOTAL_LABEL);
        totalLabel.getStyleClass().add("text-body");
        totalLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(totalLabel, Priority.ALWAYS);
        Label total = new Label();
        total.setId("cartTotal");
        total.getStyleClass().addAll("price", "text-h2");
        total.textProperty().bind(presenter.totalTextProperty());
        HBox totalRow = new HBox(totalLabel, total);
        totalRow.getStyleClass().add("cart-total-row");

        Label notice = new Label(CartDrawerPresenter.MSG_NOT_ORDERABLE);
        notice.setId("cartNotOrderable");
        notice.getStyleClass().add("text-body");
        notice.setWrapText(true);
        // Nhãn xuống dòng: chiều cao tối thiểu = chiều cao ưa thích theo chiều rộng thật, nếu không lần mở đầu bị cắt "…"
        notice.setMinHeight(Region.USE_PREF_SIZE);
        notice.setMaxWidth(Double.MAX_VALUE);
        show(notice, presenter.showNotOrderableNoticeProperty());

        Button checkout = new Button(CHECKOUT_TEXT);
        checkout.setId("checkoutButton");
        checkout.getStyleClass().addAll("button-primary", "pill");
        checkout.setMaxWidth(Double.MAX_VALUE);
        checkout.setOnAction(event -> presenter.checkout());
        // Khóa trong lúc còn dòng đang ghi: đặt hàng khi giỏ chưa ghi xong có thể đọc giỏ cũ (xem checkout())
        checkout.disableProperty().bind(Bindings.isNotEmpty(presenter.writingIds()));
        show(checkout, presenter.orderableProperty());

        VBox footer = new VBox(totalRow, notice, checkout);
        footer.setId("cartFooter");
        footer.getStyleClass().add("drawer-footer");
        show(footer, presenter.loadedProperty().and(presenter.emptyProperty().not()));
        return footer;
    }

    // ------------------------------------------------------------------ dòng giỏ

    /** Dựng lại mọi dòng từ presenter, rồi trả focus cho điều khiển vừa dùng (nếu có). */
    private void rebuild() {
        rows.clear();
        lineBox.getChildren().clear();
        for (CartLineView line : presenter.lines()) {
            RowControls controls = buildRow(line);
            rows.put(line.productId(), controls);
            lineBox.getChildren().add(controls.row());
        }
        refreshLocks();
    }

    private RowControls buildRow(CartLineView line) {
        long id = line.productId();
        String name = line.name();

        ImageTile image = new ImageTile(images, IMAGE_WIDTH, IMAGE_HEIGHT);
        image.show(line.imageUrl(), name);
        image.setAlignment(Pos.CENTER);

        // Tên: nút mở chi tiết; dòng ngừng bán không còn chi tiết nên chỉ là chữ
        Node nameNode;
        Button nameButton = null;
        if (line.isDiscontinued()) {
            Label text = new Label(name);
            text.setId("lineLink-" + id);
            text.getStyleClass().add("cart-line-title");
            text.setMinHeight(Region.USE_PREF_SIZE);
            text.setMaxWidth(Double.MAX_VALUE);
            nameNode = text;
        } else {
            nameButton = new Button(name);
            nameButton.setId("lineLink-" + id);
            nameButton.getStyleClass().add("cart-line-name");
            nameButton.setMaxWidth(Double.MAX_VALUE);
            nameButton.setMinHeight(Region.USE_PREF_SIZE);
            nameButton.setAccessibleText(name);
            nameButton.setOnAction(event -> presenter.openProduct(id));
            nameNode = nameButton;
        }

        Label unitPrice = new Label(presenter.unitPriceText(line));
        unitPrice.getStyleClass().add("cart-line-meta");
        unitPrice.setMinHeight(Region.USE_PREF_SIZE);
        unitPrice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(unitPrice, Priority.ALWAYS);
        Button remove = new Button(REMOVE_TEXT);
        remove.setId("removeLine-" + id);
        remove.getStyleClass().add("link-button");
        remove.setAccessibleText(REMOVE_TEXT + " " + name);
        remove.setOnAction(event -> {
            pendingFocus = new FocusTarget(id, Control.REMOVE);
            presenter.remove(id);
        });
        HBox priceRow = new HBox(unitPrice, remove);
        priceRow.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(nameNode, priceRow);
        info.setSpacing(4);
        String labelText = presenter.labelFor(line);
        if (labelText != null) {
            Label label = new Label(labelText);
            label.setId("lineLabel-" + id);
            label.getStyleClass().addAll("line-label", "line-label-" + presenter.labelStyleFor(line));
            Region dot = new Region();
            dot.getStyleClass().add("stock-dot");
            label.setGraphic(dot);
            info.getChildren().add(label);
        }

        Button minus = null;
        TextField field = null;
        Button plus = null;
        Node quantityNode;
        if (line.isPurchasable()) {
            minus = new Button(MINUS_GLYPH);
            minus.setId("qtyMinus-" + id);
            minus.getStyleClass().add("stepper-button");
            minus.setAccessibleText("Giảm số lượng, " + name);
            minus.setOnAction(event -> {
                pendingFocus = new FocusTarget(id, Control.MINUS);
                presenter.decrease(id);
            });

            field = quantityField(id, name, line.quantity());

            plus = new Button(PLUS_GLYPH);
            plus.setId("qtyPlus-" + id);
            plus.getStyleClass().add("stepper-button");
            plus.setAccessibleText("Tăng số lượng, " + name);
            plus.setOnAction(event -> {
                pendingFocus = new FocusTarget(id, Control.PLUS);
                presenter.increase(id);
            });

            HBox stepper = new HBox(minus, field, plus);
            stepper.getStyleClass().add("quantity-stepper");
            quantityNode = stepper;
        } else {
            Label quantityText = new Label(QUANTITY_LABEL + ": " + line.quantity());
            quantityText.getStyleClass().add("cart-line-meta");
            quantityNode = quantityText;
        }

        Label lineTotal = new Label(presenter.lineTotalText(line));
        lineTotal.setId("lineTotal-" + id);
        lineTotal.getStyleClass().add("price");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(quantityNode, spacer, lineTotal);
        bottom.setAlignment(Pos.CENTER_LEFT);
        info.getChildren().add(bottom);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(image, info);
        row.setId("cartLine-" + id);
        row.getStyleClass().add("cart-line");
        row.setAlignment(Pos.TOP_LEFT);
        return new RowControls(row, nameButton, minus, field, plus, remove, line);
    }

    private TextField quantityField(long id, String name, int quantity) {
        TextField field = new TextField(String.valueOf(quantity));
        field.setId("qtyField-" + id);
        field.getStyleClass().add("quantity-input");
        field.setAccessibleText(QUANTITY_LABEL + ", " + name);
        // Enter gửi chữ thô; chữ sai thì presenter báo và ô trả về số hiện tại
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                if (presenter.writingIds().contains(id)) {
                    return;   // dòng đang ghi: Enter lần hai không làm gì, giữ chữ đã gõ và focus
                }
                pendingFocus = new FocusTarget(id, Control.FIELD);
                if (!presenter.submitQuantity(id, field.getText())) {
                    field.setText(String.valueOf(quantity));
                    pendingFocus = null;
                }
            }
        });
        // Rời ô mà không Enter: trả về số hiện tại, không ghi ngầm (QĐ6). Dòng đang ghi thì giữ chữ đã gõ.
        field.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused && !presenter.writingIds().contains(id)) {
                field.setText(String.valueOf(quantity));
            }
        });
        return field;
    }

    /**
     * − khóa ở 1 và + khóa ở 99 (QĐ6). Dòng đang ghi chỉ được làm mờ (class {@code cart-line-writing}), không khóa
     * điều khiển: nút bị khóa làm focus nhảy đi và Enter lần hai rơi vào "Đóng"; presenter tự bỏ qua thao tác lên
     * dòng đang ghi.
     */
    private void refreshLocks() {
        for (RowControls controls : rows.values()) {
            long id = controls.line().productId();
            boolean writing = presenter.writingIds().contains(id);
            int quantity = controls.line().quantity();
            setDisabled(controls.minus(), quantity <= 1);
            setDisabled(controls.plus(), quantity >= CartDrawerPresenter.MAX_QUANTITY);
            ObservableList<String> styles = controls.row().getStyleClass();
            if (writing && !styles.contains(WRITING_CLASS)) {
                styles.add(WRITING_CLASS);
            } else if (!writing) {
                styles.remove(WRITING_CLASS);
            }
        }
    }

    private static void setDisabled(Node node, boolean disabled) {
        if (node != null) {
            node.setDisable(disabled);
        }
    }

    /** Trả focus cho điều khiển vừa dùng; dòng đã mất hoặc điều khiển bị khóa thì thử điều khiển kế, cuối cùng "Đóng". */
    private void restoreFocus() {
        FocusTarget target = pendingFocus;
        pendingFocus = null;
        if (target == null || getScene() == null) {
            return;
        }
        RowControls controls = rows.get(target.productId());
        if (controls != null) {
            Node[] order = switch (target.control()) {
                case MINUS -> new Node[] {controls.minus(), controls.field(), controls.plus()};
                case PLUS -> new Node[] {controls.plus(), controls.field(), controls.minus()};
                case FIELD -> new Node[] {controls.field()};
                case REMOVE -> new Node[] {controls.remove()};
            };
            for (Node node : order) {
                if (node != null && !node.isDisabled()) {
                    node.requestFocus();
                    return;
                }
            }
        }
        close.requestFocus();
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ------------------------------------------------------------------ mở drawer từ nút giỏ

    /**
     * Gắn nút giỏ ở thanh trên: bấm thì mở drawer. Mỗi lần mở lấy một presenter mới ({@code prototype}), dựng
     * {@link CartDrawer}, đưa cho {@link DrawerHost} và nạp giỏ; {@code DrawerHost} gọi
     * {@link CartDrawerPresenter#dispose} khi drawer đóng. {@code ShellPresenter.trigger(ShellAction.CART)} (toast
     * "Xem giỏ hàng") đi qua cùng hành động này.
     */
    @Component
    static final class Opener implements ShellContributor {

        private final ObjectProvider<CartDrawerPresenter> presenters;
        private final DrawerHost drawerHost;
        private final ImageLoader images;

        Opener(ObjectProvider<CartDrawerPresenter> presenters, DrawerHost drawerHost, ImageLoader images) {
            this.presenters = presenters;
            this.drawerHost = drawerHost;
            this.images = images;
        }

        @Override
        public void contribute(ShellPresenter shell) {
            shell.setAction(ShellAction.CART, this::open);
        }

        /** Mở drawer (FX thread). Đang mở thì {@link DrawerHost} thay nội dung bằng một giỏ mới nạp. */
        void open() {
            CartDrawerPresenter presenter = presenters.getObject();
            CartDrawer drawer = new CartDrawer(presenter, images);
            drawerHost.open(drawer, presenter::dispose);
            drawer.focusClose();
            presenter.load();
        }
    }
}
