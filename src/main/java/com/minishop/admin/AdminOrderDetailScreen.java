package com.minishop.admin;

import com.minishop.admin.AdminOrderDetailPresenter.Detail;
import com.minishop.admin.AdminOrderDetailPresenter.LineRow;
import com.minishop.desktop.LoadError;
import com.minishop.desktop.ScreenFactory;
import com.minishop.order.StatusAction;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn chi tiết một đơn của admin (spec 006 AC-006-40 đến -45, F5 của D5): tiêu đề "Đơn hàng #{id}" với nhãn trạng
 * thái (chữ kèm chấm màu) và "Quay lại danh sách đơn"; khối "Thông tin nhận hàng" (người nhận, số điện thoại, địa
 * chỉ, ghi chú khi có, phương thức thanh toán); khối "Thông tin đơn hàng" ("Ngày đặt", "Cập nhật lần cuối", "Tài
 * khoản đặt hàng"); bảng dòng hàng với "Tổng cộng"; khối "Cập nhật trạng thái" với một nút cho mỗi bước được phép
 * hoặc "Đơn hàng đã ở trạng thái cuối.". Chỉ dựng node và gắn vào {@link AdminOrderDetailPresenter}; không gọi
 * service và không tự quyết bước nào được phép (chỉ lặp {@code actions}).
 *
 * <p><b>Văn bản của người dùng</b> (tên, địa chỉ, ghi chú, tên sản phẩm, email) chỉ hiện trong {@code Label}, không
 * diễn giải HTML, và tự xuống dòng kể cả chuỗi dài không có dấu cách, nên không tràn ngang ở 1024 px.
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên focus ở "Quay lại danh sách đơn". Sau một lần đổi trạng thái (các nút được
 * dựng lại nên nút vừa bấm biến mất) focus về "Quay lại danh sách đơn" và trang cuộn lên đầu, như màn chi tiết đơn
 * của khách (D4).
 * Nút đang có focus không bị khóa trong lúc gửi; presenter tự bỏ qua lần bấm thứ hai và màn hiện vòng quay nhỏ.
 * Sau lỗi lạ của lần nạp đầu (D6, QĐ12) màn hiện khối lỗi thay các thẻ và focus ở "Thử lại"; "Quay lại danh sách
 * đơn" ở đầu trang vẫn dùng được.
 *
 * <p>Id cố định cho test: {@code adminOrderDetailScreen}, {@code adminOrderTitle}, {@code adminOrderStatus},
 * {@code adminOrderDetailLoadError}, {@code adminOrderDetailRetry},
 * {@code adminBackToOrders}, {@code adminOrderReceiver}, {@code adminOrderPhone}, {@code adminOrderAddress},
 * {@code adminOrderNote}, {@code adminOrderPayment}, {@code adminOrderCreated}, {@code adminOrderUpdated},
 * {@code adminOrderAccount}, {@code adminOrderLines} (bảng), {@code adminOrderTotal}, {@code adminOrderActions}
 * (nhóm nút), {@code statusAction-{TARGET}}, {@code adminOrderFinal}, {@code adminOrderBusy},
 * {@code adminOrderLoading}; cộng các id của thanh bên ({@link AdminScreen}).
 */
final class AdminOrderDetailScreen extends AdminScreen {

    static final String BACK_TEXT = "Quay lại danh sách đơn";
    static final String RECEIVER_SECTION = "Thông tin nhận hàng";
    static final String RECEIVER_LABEL = "Họ tên người nhận";
    static final String PHONE_LABEL = "Số điện thoại";
    static final String ADDRESS_LABEL = "Địa chỉ giao hàng";
    static final String NOTE_LABEL = "Ghi chú";
    static final String PAYMENT_LABEL = "Phương thức thanh toán";
    static final String INFO_SECTION = "Thông tin đơn hàng";
    static final String CREATED_LABEL = "Ngày đặt";
    static final String UPDATED_LABEL = "Cập nhật lần cuối";
    static final String ACCOUNT_LABEL = "Tài khoản đặt hàng";
    static final String LINES_LABEL = "Sản phẩm trong đơn hàng";
    static final String COLUMN_PRODUCT = "Sản phẩm";
    static final String COLUMN_UNIT_PRICE = "Đơn giá";
    static final String COLUMN_QUANTITY = "Số lượng";
    static final String COLUMN_LINE_TOTAL = "Thành tiền";
    static final String TOTAL_LABEL = "Tổng cộng";
    static final String STATUS_SECTION = "Cập nhật trạng thái";
    static final String FINAL_TEXT = "Đơn hàng đã ở trạng thái cuối.";

    private static final double KEY_WIDTH = 200;

    private static final List<ColumnConstraints> LINE_COLUMNS = List.of(
            growingColumn(120), fixedColumn(130), fixedColumn(100), fixedColumn(140));

    private final AdminOrderDetailPresenter presenter;

    private final Button back = new Button(BACK_TEXT);
    private final HBox statusHolder = new HBox();
    private final Label receiver = valueLabel("adminOrderReceiver");
    private final Label phone = valueLabel("adminOrderPhone");
    private final Label address = valueLabel("adminOrderAddress");
    private final Label note = valueLabel("adminOrderNote");
    private final Label noteKey = keyLabel(NOTE_LABEL);
    private final Label payment = valueLabel("adminOrderPayment");
    private final Label created = valueLabel("adminOrderCreated");
    private final Label updated = valueLabel("adminOrderUpdated");
    private final Label account = valueLabel("adminOrderAccount");
    private final Label total = new Label();
    private final VBox lines = new VBox();
    private final HBox actionButtons = new HBox();
    private final Label finalText = new Label(FINAL_TEXT);
    private final VBox loadError;
    private boolean pendingFocus = true;
    private boolean retryFocusPending;
    private boolean actionHadFocus;
    private Detail detailAtAction;

    AdminOrderDetailScreen(AdminOrderDetailPresenter presenter, AdminNavPresenter nav) {
        super(AdminSection.ORDERS, nav);
        this.presenter = presenter;
        setId("adminOrderDetailScreen");

        back.setId("adminBackToOrders");
        back.getStyleClass().addAll("button-secondary", "pill");
        back.setOnAction(event -> presenter.back());

        Label title = pageTitle("", "adminOrderTitle");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        title.textProperty().bind(presenter.titleTextProperty());
        statusHolder.setAlignment(Pos.CENTER_LEFT);
        HBox head = pageHead(title, statusHolder, back);

        HBox loadingBox = loadingBox("adminOrderLoading",
                presenter.loadingProperty().and(presenter.detailProperty().isNull()));

        VBox receiverCard = buildReceiverCard();
        VBox infoCard = buildInfoCard();
        VBox linesCard = buildLinesCard();
        VBox statusCard = buildStatusCard();
        for (Node card : new Node[] {receiverCard, infoCard, linesCard, statusCard}) {
            show(card, presenter.detailProperty().isNotNull());
        }

        loadError = LoadError.create("adminOrderDetail", presenter::retry);
        show(loadError, presenter.loadFailedProperty());

        setBody(page(head, loadingBox, loadError, receiverCard, infoCard, linesCard, statusCard));

        presenter.loadFailedProperty().addListener((observable, wasFailed, isFailed) -> {
            if (isFailed) {
                retryFocusPending = true;
                Platform.runLater(this::tryFocus);
            }
        });

        presenter.detailProperty().addListener((observable, oldDetail, newDetail) -> render());
        presenter.busyProperty().addListener((observable, wasBusy, isBusy) -> {
            if (!isBusy) {
                restoreFocusAfterChange();
            }
        });
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng, nên theo dõi nút "Quay lại" để biết lúc focus
        back.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
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
    }

    // ------------------------------------------------------------------ dựng node

    /** Thẻ "Thông tin nhận hàng": người nhận, số điện thoại, địa chỉ, ghi chú (khi có), phương thức thanh toán. */
    private VBox buildReceiverCard() {
        Label section = sectionTitle(RECEIVER_SECTION);
        GridPane list = infoList();
        addRow(list, 0, keyLabel(RECEIVER_LABEL), receiver);
        addRow(list, 1, keyLabel(PHONE_LABEL), phone);
        addRow(list, 2, keyLabel(ADDRESS_LABEL), address);
        addRow(list, 3, noteKey, note);
        addRow(list, 4, keyLabel(PAYMENT_LABEL), payment);
        VBox card = new VBox(section, list);
        card.getStyleClass().add("summary-card");
        return card;
    }

    /** Thẻ "Thông tin đơn hàng": ngày đặt, cập nhật lần cuối, tài khoản đặt hàng. */
    private VBox buildInfoCard() {
        Label section = sectionTitle(INFO_SECTION);
        GridPane list = infoList();
        addRow(list, 0, keyLabel(CREATED_LABEL), created);
        addRow(list, 1, keyLabel(UPDATED_LABEL), updated);
        addRow(list, 2, keyLabel(ACCOUNT_LABEL), account);
        VBox card = new VBox(section, list);
        card.getStyleClass().add("summary-card");
        return card;
    }

    /** Thẻ dòng hàng: bảng và "Tổng cộng". */
    private VBox buildLinesCard() {
        Label section = sectionTitle(LINES_LABEL);
        lines.setId("adminOrderLines");
        lines.setAccessibleText(LINES_LABEL);

        Label totalKey = new Label(TOTAL_LABEL);
        totalKey.getStyleClass().add("section-title");
        total.setId("adminOrderTotal");
        total.getStyleClass().addAll("price", "text-h2");
        total.textProperty().bind(presenter.detailProperty().map(Detail::totalText).orElse(""));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox totalRow = new HBox(totalKey, spacer, total);
        totalRow.setAlignment(Pos.CENTER_LEFT);
        totalRow.getStyleClass().add("table-total-row");

        VBox card = new VBox(section, lines, totalRow);
        card.getStyleClass().add("summary-card");
        return card;
    }

    /** Thẻ "Cập nhật trạng thái": một nút cho mỗi bước được phép, hoặc câu "đã ở trạng thái cuối", và vòng quay. */
    private VBox buildStatusCard() {
        Label section = sectionTitle(STATUS_SECTION);

        actionButtons.setId("adminOrderActions");
        actionButtons.getStyleClass().add("admin-actions");

        finalText.setId("adminOrderFinal");
        finalText.getStyleClass().addAll("text-body", "text-muted");
        finalText.setWrapText(true);
        finalText.setMinHeight(Region.USE_PREF_SIZE);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setId("adminOrderBusy");
        spinner.setAccessibleText(LOADING_TEXT);
        spinner.setPrefSize(24, 24);
        spinner.setMinSize(24, 24);
        spinner.setMaxSize(24, 24);
        HBox busyBox = new HBox(spinner);
        busyBox.getStyleClass().add("admin-busy");
        busyBox.setAlignment(Pos.CENTER_LEFT);
        show(busyBox, presenter.busyProperty());
        show(spinner, presenter.busyProperty());

        VBox card = new VBox(section, actionButtons, finalText, busyBox);
        card.getStyleClass().add("summary-card");
        return card;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static GridPane infoList() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("info-list");
        ColumnConstraints key = new ColumnConstraints(KEY_WIDTH, KEY_WIDTH, KEY_WIDTH);
        key.setHgrow(Priority.NEVER);
        // Cột giá trị bắt đầu hẹp và co giãn: nhãn xuống dòng theo chiều rộng thật, chuỗi dài không đẩy tràn ngang
        ColumnConstraints value = new ColumnConstraints(0, 100, Double.MAX_VALUE);
        value.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(key, value);
        return grid;
    }

    private static void addRow(GridPane grid, int row, Node key, Node value) {
        grid.add(key, 0, row);
        grid.add(value, 1, row);
        GridPane.setValignment(key, VPos.TOP);
        GridPane.setValignment(value, VPos.TOP);
        GridPane.setHgrow(value, Priority.ALWAYS);
    }

    private static Label keyLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("info-key");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label valueLabel(String id) {
        Label label = new Label();
        label.setId(id);
        label.getStyleClass().add("info-value");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinWidth(0);
        return label;
    }

    // ------------------------------------------------------------------ nội dung theo đơn

    /** Điền mọi trường theo {@link Detail} hiện tại và dựng lại bảng dòng hàng và các nút trạng thái. */
    private void render() {
        Detail detail = presenter.detailProperty().get();
        if (detail == null) {
            return;
        }
        Label pill = statusPill(detail.statusLabel(), detail.statusKey());
        pill.setId("adminOrderStatus");
        statusHolder.getChildren().setAll(pill);

        receiver.setText(detail.receiverName());
        phone.setText(detail.phone());
        address.setText(detail.address());
        note.setText(detail.note());
        for (Node node : new Node[] {note, noteKey}) {
            node.setVisible(detail.hasNote());
            node.setManaged(detail.hasNote());
        }
        payment.setText(detail.paymentText());
        created.setText(detail.createdText());
        updated.setText(detail.updatedText());
        account.setText(detail.accountText());

        renderLines(detail);
        renderActions(detail);
        Platform.runLater(this::tryFocus);
    }

    private void renderLines(Detail detail) {
        lines.getChildren().clear();
        GridPane header = headerRow(LINE_COLUMNS, COLUMN_PRODUCT, COLUMN_UNIT_PRICE, COLUMN_QUANTITY,
                COLUMN_LINE_TOTAL);
        for (Node label : header.getChildren()) {
            if (GridPane.getColumnIndex(label) != null && GridPane.getColumnIndex(label) > 0) {
                GridPane.setHalignment(label, HPos.RIGHT);
            }
        }
        lines.getChildren().add(header);
        for (LineRow line : detail.lines()) {
            GridPane row = dataRow(LINE_COLUMNS, false);
            Label name = cell(line.productName(), "text-body");
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            put(row, name, 0);
            putNumber(row, line.unitPriceText(), 1);
            putNumber(row, line.quantityText(), 2);
            putNumber(row, line.lineTotalText(), 3);
            lines.getChildren().add(row);
        }
    }

    private static void putNumber(GridPane row, String text, int column) {
        Label label = new Label(text);
        label.getStyleClass().add("text-body");
        put(row, label, column);
        GridPane.setHalignment(label, HPos.RIGHT);
    }

    private void renderActions(Detail detail) {
        actionButtons.getChildren().clear();
        for (StatusAction action : detail.actions()) {
            Button button = new Button(action.label());
            button.setId("statusAction-" + action.targetStatus());
            button.getStyleClass().addAll(action.danger() ? "button-danger" : "button-primary", "pill");
            // Không khóa nút trong lúc gửi: nút bị khóa làm focus nhảy đi; presenter tự bỏ qua lần bấm thứ hai
            button.setOnAction(event -> {
                actionHadFocus = true;
                detailAtAction = presenter.detailProperty().get();
                presenter.runAction(action);
            });
            actionButtons.getChildren().add(button);
        }
        boolean none = detail.actions().isEmpty();
        actionButtons.setVisible(!none);
        actionButtons.setManaged(!none);
        finalText.setVisible(none);
        finalText.setManaged(none);
    }

    // ------------------------------------------------------------------ focus

    /**
     * Một thao tác đổi trạng thái vừa xong và đơn đã nạp lại (các nút được dựng lại, nút vừa bấm không còn): đưa focus
     * về "Quay lại danh sách đơn" ở đầu trang. Thao tác lỗi (đơn không được nạp lại) thì các nút còn nguyên và giữ
     * focus; hộp xác nhận bị từ chối thì chưa có gì gửi đi nên không tới đây.
     */
    private void restoreFocusAfterChange() {
        boolean hadFocus = actionHadFocus;
        Detail before = detailAtAction;
        actionHadFocus = false;
        detailAtAction = null;
        if (hadFocus && presenter.detailProperty().get() != before) {
            focusBack();
        }
    }

    /**
     * Đưa focus về "Quay lại danh sách đơn" sau một nhịp (để việc JavaFX đẩy focus đi xong trước). Cửa sổ chưa có
     * focus (hộp xác nhận vừa đóng) thì làm thêm một lần khi cửa sổ nhận lại focus. Nút nằm ở đầu trang, nên cuộn
     * lên đầu để thấy vòng focus.
     */
    private void focusBack() {
        Platform.runLater(this::focusBackAtTop);
        Window window = getScene() == null ? null : getScene().getWindow();
        if (window != null && !window.isFocused()) {
            window.focusedProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Boolean> observable, Boolean was, Boolean now) {
                    if (now) {
                        observable.removeListener(this);
                        Platform.runLater(AdminOrderDetailScreen.this::focusBackAtTop);
                    }
                }
            });
        }
    }

    private void focusBackAtTop() {
        back.requestFocus();
        scrollToTop();
    }

    private void tryFocus() {
        if (retryFocusPending && presenter.loadFailedProperty().get()) {
            // Sau lỗi nạp đầu: focus vào "Thử lại" (QĐ12); "Quay lại danh sách đơn" ở đầu trang vẫn dùng được
            Button retry = LoadError.retryButton(loadError);
            if (retry.getScene() != null) {
                retryFocusPending = false;
                retry.requestFocus();
            }
            return;
        }
        if (!pendingFocus || back.getScene() == null || presenter.detailProperty().get() == null) {
            return;
        }
        pendingFocus = false;
        back.requestFocus();
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminOrderDetailRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminOrderDetailRoute> {

        private final ObjectProvider<AdminOrderDetailPresenter> presenters;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminOrderDetailPresenter> presenters, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.nav = nav;
        }

        @Override
        public Class<AdminOrderDetailRoute> routeType() {
            return AdminOrderDetailRoute.class;
        }

        @Override
        public Node create(AdminOrderDetailRoute route) {
            AdminOrderDetailPresenter presenter = presenters.getObject();
            AdminOrderDetailScreen screen = new AdminOrderDetailScreen(presenter, nav);
            presenter.load(route);
            return screen;
        }
    }
}
