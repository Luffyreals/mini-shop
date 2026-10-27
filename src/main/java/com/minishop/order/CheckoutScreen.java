package com.minishop.order;

import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn Thanh toán (spec 005, QĐ8, QĐ9, QĐ12 của D4): tiêu đề "Thanh toán", thông báo trên màn, cột form giao hàng
 * (họ tên, số điện thoại, địa chỉ, ghi chú; "Thanh toán khi nhận hàng (COD)"; "Xác nhận đặt hàng"; "Quay lại giỏ
 * hàng") và cột "Đơn hàng của bạn" (bảng dòng hàng và "Tổng cộng"). Chỉ dựng node và gắn vào
 * {@link CheckoutPresenter}; không gọi service.
 *
 * <p><b>Bố cục:</b> hai cột (form trái, tóm tắt phải) khi chiều rộng màn từ {@value #TWO_COLUMNS_FROM} px (cùng
 * ngưỡng với màn D3), nhỏ hơn thì xếp chồng; cuộn dọc, không cuộn ngang. Bảng là {@code GridPane}, không dùng
 * {@code TableView}.
 *
 * <p><b>Bàn phím:</b> Enter trong ô một dòng gửi form; trong ô nhiều dòng thì xuống dòng. Tab và Shift+Tab đi qua
 * các ô theo thứ tự trên màn (ô nhiều dòng không nuốt Tab). Sau khi kiểm form sai, focus tới ô lỗi đầu tiên; sau
 * khi một lần đặt hàng thất bại, focus quay về nút xác nhận. Mỗi ô có chữ cho trình đọc màn hình là nhãn của nó,
 * và lỗi (nếu có) là phần trợ giúp của ô. Trong lúc đặt hàng, các ô chỉ bị khóa chỉnh sửa và nút xác nhận vẫn nhận
 * focus (Enter lần hai không làm gì, focus không nhảy đi); Tab tới điều khiển nằm dưới nếp gập thì màn cuộn tới.
 *
 * <p>Id cố định cho test: {@code checkoutScreen}, {@code checkoutTitle}, {@code checkoutNotice},
 * {@code receiverNameField}, {@code phoneField}, {@code addressField}, {@code noteField},
 * {@code receiverNameError}, {@code phoneError}, {@code addressError}, {@code noteError},
 * {@code paymentMethod}, {@code confirmOrder}, {@code backToCart}, {@code summaryLines}, {@code checkoutTotal},
 * {@code checkoutLoading}, {@code checkoutBusy}.
 */
final class CheckoutScreen extends ScrollPane {

    static final String TITLE = "Thanh toán";
    static final String RECEIVER_NAME_LABEL = "Họ tên người nhận";
    static final String PHONE_LABEL = "Số điện thoại";
    static final String ADDRESS_LABEL = "Địa chỉ giao hàng";
    static final String NOTE_LABEL = "Ghi chú (không bắt buộc)";
    static final String PAYMENT_LABEL = "Phương thức thanh toán";
    static final String CONFIRM_TEXT = "Xác nhận đặt hàng";
    static final String BACK_TEXT = "Quay lại giỏ hàng";
    static final String SUMMARY_TITLE = "Đơn hàng của bạn";
    static final String SUMMARY_TABLE_TEXT = "Sản phẩm trong đơn hàng";
    static final String COLUMN_PRODUCT = "Sản phẩm";
    static final String COLUMN_QUANTITY = "Số lượng";
    static final String COLUMN_UNIT_PRICE = "Đơn giá";
    static final String COLUMN_LINE_TOTAL = "Thành tiền";
    static final String TOTAL_LABEL = "Tổng cộng";
    static final String BUSY_TEXT = "Đang đặt hàng";

    /** Từ chiều rộng này trở lên là hai cột (cùng ngưỡng với lưới và trang chi tiết của D3). */
    static final int TWO_COLUMNS_FROM = 860;

    private static final double SPINNER_SIZE = 22;
    private static final double LINK_INSET = 12;
    private static final double FOCUS_MARGIN = 16;
    private static final double PRODUCT_COLUMN_MIN_WIDTH = 90;
    private static final String TICK_PATH = "M4 11 L9 16 L20 5";
    /** Vòng tròn 18 px có dấu chấm than, cùng cỡ với dấu tích; đường chấm than đứng một mình chỉ rộng 2,5 px. */
    private static final String WARNING_PATH =
            "M3 12 A9 9 0 1 0 21 12 A9 9 0 1 0 3 12 Z M12 7 L12 13 M12 16.5 L12 16.6";

    private final CheckoutPresenter presenter;

    private final GridPane layout = new GridPane();
    private final VBox form = new VBox();
    private final VBox summary = new VBox();
    private final GridPane table = new GridPane();
    private final SVGPath noticeIcon = new SVGPath();
    private final Label notice = new Label();
    private final TextField receiverName = new TextField();
    private final TextField phone = new TextField();
    private final TextArea address = new TextArea();
    private final TextArea note = new TextArea();
    private final Button confirm = new Button(CONFIRM_TEXT);
    private final Button back = new Button(BACK_TEXT);

    private boolean stacked;
    private boolean pendingFocus = true;
    private final ChangeListener<Node> focusOwnerListener;

    CheckoutScreen(CheckoutPresenter presenter) {
        this.presenter = presenter;

        setId("checkoutScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        Label title = new Label(TITLE);
        title.setId("checkoutTitle");
        title.getStyleClass().add("page-title");
        title.setAccessibleRole(AccessibleRole.TEXT);

        buildNotice();
        buildForm();
        buildSummary();

        layout.getStyleClass().add("checkout-layout");
        layout.getChildren().addAll(form, summary);
        place(false);
        show(layout, presenter.readyProperty());

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("checkoutLoading");
        indicator.setAccessibleText("Đang tải");
        HBox loadingBox = new HBox(indicator);
        loadingBox.getStyleClass().add("loading");
        // Vòng chờ chỉ khi đang nạp lần đầu; nạp lần đầu hỏng thì presenter đưa người dùng về trang chủ
        show(loadingBox, presenter.readyProperty().not().and(presenter.loadingProperty()));

        VBox page = new VBox(title, notice, loadingBox, layout);
        page.getStyleClass().add("page-root");
        setContent(page);

        widthProperty().addListener((observable, oldWidth, newWidth) -> updateLayout(newWidth.doubleValue()));
        presenter.lines().addListener((ListChangeListener<CheckoutLineView>) change -> renderLines());
        presenter.firstErrorFieldProperty().addListener((observable, oldField, field) -> {
            if (field != null) {
                inputFor(field).requestFocus();
            }
        });
        presenter.readyProperty().addListener((observable, wasReady, isReady) -> {
            if (isReady) {
                Platform.runLater(this::focusFirstEmpty);
            }
        });
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng; dữ liệu có thể về trước lúc đó, và requestFocus
        // trên node chưa có scene thì không làm gì. Nên thử lại khi ô đầu tiên vào scene.
        receiverName.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::focusFirstEmpty);
            }
        });
        // Đặt hàng hỏng thì form mở lại; đưa focus về nút xác nhận (nút luôn giữ focus được, nên thường đã ở đó)
        presenter.busyProperty().addListener((observable, wasBusy, isBusy) -> {
            if (wasBusy && !isBusy) {
                Platform.runLater(() -> {
                    if (getScene() != null && !confirm.isDisabled()) {
                        confirm.requestFocus();
                    }
                });
            }
        });
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            }
        });
        // Tab tới nút "Xác nhận đặt hàng" nằm dưới nếp gập thì cuộn tới (chỉ khi nó ngoài vùng nhìn)
        focusOwnerListener = (observable, oldOwner, newOwner) -> scrollIntoView(newOwner);
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.focusOwnerProperty().removeListener(focusOwnerListener);
            }
            if (newScene != null) {
                newScene.focusOwnerProperty().addListener(focusOwnerListener);
            }
        });

        renderLines();
    }

    // ------------------------------------------------------------------ thông báo trên màn

    private void buildNotice() {
        noticeIcon.getStyleClass().add("form-notice-icon");
        notice.setId("checkoutNotice");
        notice.getStyleClass().addAll("form-notice", "info-card");
        notice.setGraphic(noticeIcon);
        notice.setMinHeight(Region.USE_PREF_SIZE);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.textProperty().bind(presenter.noticeTextProperty());
        notice.accessibleTextProperty().bind(presenter.noticeTextProperty());
        showWhenNotEmpty(notice, presenter.noticeTextProperty());
        presenter.noticeKindProperty().addListener((observable, oldKind, newKind) -> renderNoticeKind());
        renderNoticeKind();
    }

    private void renderNoticeKind() {
        boolean error = presenter.noticeKindProperty().get() == CheckoutPresenter.NoticeKind.ERROR;
        noticeIcon.setContent(error ? WARNING_PATH : TICK_PATH);
        notice.getStyleClass().removeAll("form-notice-error", "form-notice-success");
        notice.getStyleClass().add(error ? "form-notice-error" : "form-notice-success");
    }

    // ------------------------------------------------------------------ cột form

    private void buildForm() {
        receiverName.setId("receiverNameField");
        phone.setId("phoneField");
        address.setId("addressField");
        note.setId("noteField");

        receiverName.textProperty().bindBidirectional(presenter.receiverNameProperty());
        phone.textProperty().bindBidirectional(presenter.phoneProperty());
        address.textProperty().bindBidirectional(presenter.shippingAddressProperty());
        note.textProperty().bindBidirectional(presenter.noteProperty());

        receiverName.setOnAction(event -> presenter.confirm());
        phone.setOnAction(event -> presenter.confirm());
        for (TextArea area : new TextArea[] {address, note}) {
            area.setPrefRowCount(3);
            area.setWrapText(true);
        }
        tabThrough(address, phone, note);
        tabThrough(note, address, confirm, back);

        Label paymentLabel = new Label(PAYMENT_LABEL);
        paymentLabel.getStyleClass().add("field-label");
        Label payment = new Label(PaymentMethod.COD.label());
        payment.setId("paymentMethod");
        payment.getStyleClass().add("text-body");
        payment.setAccessibleText(PAYMENT_LABEL + ": " + PaymentMethod.COD.label());
        VBox paymentBox = new VBox(6, paymentLabel, payment);

        confirm.setId("confirmOrder");
        confirm.getStyleClass().addAll("pill", "button-primary");
        confirm.disableProperty().bind(presenter.confirmDisabledBinding());
        confirm.setOnAction(event -> presenter.confirm());
        enterFires(confirm);

        ProgressIndicator busy = new ProgressIndicator();
        busy.setId("checkoutBusy");
        busy.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setMinSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setAccessibleText(BUSY_TEXT);
        busy.visibleProperty().bind(presenter.busyProperty());
        busy.managedProperty().bind(busy.visibleProperty());
        HBox actions = new HBox(12, confirm, busy);
        actions.setAlignment(Pos.CENTER_LEFT);

        back.setId("backToCart");
        back.getStyleClass().add("link-button");
        back.setMaxWidth(Region.USE_PREF_SIZE);
        back.setTranslateX(-LINK_INSET);   // đệm trái của .link-button (12) bù đi để chữ thẳng mép với ô nhập
        back.disableProperty().bind(presenter.busyProperty());
        back.setOnAction(event -> presenter.backToCart());
        enterFires(back);

        form.getChildren().addAll(
                field(RECEIVER_NAME_LABEL, receiverName, "receiverNameError",
                        presenter.receiverNameErrorProperty()),
                field(PHONE_LABEL, phone, "phoneError", presenter.phoneErrorProperty()),
                field(ADDRESS_LABEL, address, "addressError", presenter.shippingAddressErrorProperty()),
                field(NOTE_LABEL, note, "noteError", presenter.noteErrorProperty()),
                paymentBox, actions, back);
        form.setSpacing(16);
        form.getStyleClass().add("summary-card");
    }

    /**
     * Nhãn, ô nhập và dòng lỗi ngay dưới ô. Ô nhận nhãn làm chữ cho trình đọc màn hình và dòng lỗi làm phần trợ
     * giúp, nên trình đọc đọc lỗi cùng ô; ô bị khóa khi đang đặt hàng.
     */
    private VBox field(String labelText, TextInputControl input, String errorId, ReadOnlyStringProperty error) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setLabelFor(input);

        input.setAccessibleText(labelText);
        input.accessibleHelpProperty().bind(error);
        // Chỉ khóa chỉnh sửa (không disable): ô còn focus nên Enter lần hai không làm focus nhảy sang nút khác
        input.editableProperty().bind(presenter.busyProperty().not());

        Label errorLabel = new Label();
        errorLabel.setId(errorId);
        errorLabel.getStyleClass().add("field-error");
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);
        errorLabel.textProperty().bind(error);
        showWhenNotEmpty(errorLabel, error);

        return new VBox(6, label, input, errorLabel);
    }

    private TextInputControl inputFor(CheckoutPresenter.Field field) {
        return switch (field) {
            case RECEIVER_NAME -> receiverName;
            case PHONE -> phone;
            case ADDRESS -> address;
            case NOTE -> note;
        };
    }

    /** Ô nhiều dòng giữ Tab để thụt dòng; ở đây Tab và Shift+Tab chuyển focus giữa các ô như mọi ô khác. */
    private static void tabThrough(TextArea area, Node previous, Node... forward) {
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isAltDown()
                    && !event.isMetaDown()) {
                event.consume();
                if (event.isShiftDown()) {
                    previous.requestFocus();
                    return;
                }
                for (Node target : forward) {
                    if (!target.isDisabled()) {
                        target.requestFocus();
                        return;
                    }
                }
            }
        });
    }

    private static void enterFires(Button button) {
        button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                button.fire();
            }
        });
    }

    // ------------------------------------------------------------------ cột tóm tắt

    private void buildSummary() {
        Label title = new Label(SUMMARY_TITLE);
        title.setId("summaryTitle");
        title.getStyleClass().add("text-h2");
        title.setAccessibleRole(AccessibleRole.TEXT);

        table.setId("summaryLines");
        table.getStyleClass().addAll("data-table", "summary-table");
        table.setAccessibleText(SUMMARY_TABLE_TEXT);
        ColumnConstraints product = new ColumnConstraints();
        product.setHgrow(Priority.ALWAYS);
        product.setMinWidth(PRODUCT_COLUMN_MIN_WIDTH);
        table.getColumnConstraints().add(product);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setHalignment(HPos.RIGHT);
            column.setMinWidth(Region.USE_PREF_SIZE);
            table.getColumnConstraints().add(column);
        }

        Label totalLabel = new Label(TOTAL_LABEL);
        totalLabel.getStyleClass().add("text-h2");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label total = new Label();
        total.setId("checkoutTotal");
        total.getStyleClass().addAll("price", "text-h2");
        total.textProperty().bind(presenter.totalTextProperty());
        total.accessibleTextProperty().bind(presenter.totalTextProperty().map(text -> TOTAL_LABEL + " " + text));
        HBox totalRow = new HBox(12, totalLabel, spacer, total);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        summary.getChildren().addAll(title, table, totalRow);
        summary.setSpacing(16);
        summary.getStyleClass().add("summary-card");
    }

    /** Dựng lại bảng: hàng tiêu đề cột đúng chữ spec 005, rồi mỗi dòng hàng một hàng. */
    private void renderLines() {
        table.getChildren().clear();
        String[] headers = {COLUMN_PRODUCT, COLUMN_QUANTITY, COLUMN_UNIT_PRICE, COLUMN_LINE_TOTAL};
        for (int column = 0; column < headers.length; column++) {
            Label header = new Label(headers[column]);
            header.getStyleClass().addAll("text-caption", "text-muted", "summary-cell");
            table.add(header, column, 0);
        }
        int row = 1;
        for (CheckoutLineView line : presenter.lines()) {
            Label name = new Label(line.name());
            name.getStyleClass().addAll("text-body", "summary-cell");
            name.setWrapText(true);
            name.setMinHeight(Region.USE_PREF_SIZE);
            Label quantity = cell(Integer.toString(line.quantity()));
            Label unitPrice = cell(presenter.money(line.unitPrice()));
            Label lineTotal = cell(presenter.money(line.lineTotal()));
            GridPane.setValignment(name, VPos.TOP);
            table.add(name, 0, row);
            table.add(quantity, 1, row);
            table.add(unitPrice, 2, row);
            table.add(lineTotal, 3, row);
            row++;
        }
    }

    private static Label cell(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("text-body", "summary-cell");
        GridPane.setValignment(label, VPos.TOP);
        return label;
    }

    // ------------------------------------------------------------------ bố cục 2 cột hoặc xếp chồng

    private void updateLayout(double width) {
        if (width <= 0) {
            return;
        }
        boolean wantStacked = width < TWO_COLUMNS_FROM;
        if (wantStacked != stacked) {
            place(wantStacked);
        }
    }

    private void place(boolean stack) {
        stacked = stack;
        layout.getColumnConstraints().clear();
        int columns = stack ? 1 : 2;
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(stack ? 100 : 50);
            column.setHgrow(Priority.ALWAYS);
            layout.getColumnConstraints().add(column);
        }
        GridPane.setColumnIndex(form, 0);
        GridPane.setRowIndex(form, 0);
        GridPane.setValignment(form, VPos.TOP);
        GridPane.setFillWidth(form, true);
        GridPane.setColumnIndex(summary, stack ? 0 : 1);
        GridPane.setRowIndex(summary, stack ? 1 : 0);
        GridPane.setValignment(summary, VPos.TOP);
        GridPane.setFillWidth(summary, true);
    }

    // ------------------------------------------------------------------ focus

    /**
     * Sau lần nạp đầu: focus vào ô trống đầu tiên (họ tên đã điền sẵn thì tới số điện thoại). Chỉ chạy một lần, khi
     * đã có dữ liệu và các ô đã nằm trong scene (gọi từ cả hai sự kiện, cái nào tới sau thì làm).
     */
    private void focusFirstEmpty() {
        if (!pendingFocus || !presenter.readyProperty().get() || receiverName.getScene() == null) {
            return;
        }
        pendingFocus = false;
        TextInputControl[] order = {receiverName, phone, address};
        for (TextInputControl input : order) {
            if (input.getText() == null || input.getText().isBlank()) {
                input.requestFocus();
                return;
            }
        }
        confirm.requestFocus();
    }

    /**
     * Cuộn dọc đủ để {@code node} (nằm trong nội dung của màn) lọt vào vùng nhìn; đã nhìn thấy hết thì không cuộn.
     */
    private void scrollIntoView(Node node) {
        Node content = getContent();
        if (node == null || content == null || !isInside(node, content)) {
            return;
        }
        double viewport = getViewportBounds().getHeight();
        double total = content.getLayoutBounds().getHeight();
        double scrollable = total - viewport;
        if (viewport <= 0 || scrollable <= 0) {
            return;
        }
        Bounds bounds = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        if (bounds == null) {
            return;
        }
        double top = getVvalue() * scrollable;
        if (bounds.getMinY() < top) {
            top = bounds.getMinY() - FOCUS_MARGIN;
        } else if (bounds.getMaxY() > top + viewport) {
            top = bounds.getMaxY() - viewport + FOCUS_MARGIN;
        } else {
            return;
        }
        setVvalue(Math.max(0, Math.min(1, top / scrollable)));
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ gắn thuộc tính

    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    private static void showWhenNotEmpty(Label label, ReadOnlyStringProperty text) {
        label.visibleProperty().bind(text.isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link CheckoutRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<CheckoutRoute> {

        private final ObjectProvider<CheckoutPresenter> presenters;

        Factory(ObjectProvider<CheckoutPresenter> presenters) {
            this.presenters = presenters;
        }

        @Override
        public Class<CheckoutRoute> routeType() {
            return CheckoutRoute.class;
        }

        @Override
        public Node create(CheckoutRoute route) {
            CheckoutPresenter presenter = presenters.getObject();
            CheckoutScreen screen = new CheckoutScreen(presenter);
            presenter.load();
            return screen;
        }
    }
}
