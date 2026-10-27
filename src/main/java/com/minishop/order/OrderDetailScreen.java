package com.minishop.order;

import com.minishop.desktop.LoadError;
import com.minishop.desktop.ScreenFactory;
import com.minishop.desktop.StatusPill;
import com.minishop.desktop.Tables;
import com.minishop.order.OrderDetailPresenter.LineRow;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn chi tiết một đơn hàng (spec 005, AC-005-23, -27, -28, QĐ5, QĐ12 của D4): tiêu đề "Đơn hàng #{id}", dòng
 * "Đặt hàng thành công. Mã đơn của bạn là #{id}." khi vừa đặt xong, "Ngày đặt", "Trạng thái" (nhãn chữ kèm chấm
 * màu), "Phương thức thanh toán", "Thông tin nhận hàng" (người nhận, số điện thoại, địa chỉ, ghi chú nếu có),
 * bảng dòng hàng với "Tổng cộng", nút "Quay lại danh sách đơn" và, khi đơn còn {@code PENDING}, "Hủy đơn hàng".
 * Chỉ dựng node và gắn vào {@link OrderDetailPresenter}; không gọi service.
 *
 * <p><b>Văn bản của người dùng</b> (tên, địa chỉ, ghi chú) chỉ hiện trong {@code Label}, không diễn giải HTML, và
 * tự xuống dòng kể cả chuỗi dài không có dấu cách, nên không tràn ngang ở 1024 px (AC-005-33).
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên focus ở "Quay lại danh sách đơn". Hủy đơn xong (nút hủy biến mất) focus
 * cũng về nút đó; nếu việc hủy lỗi và nút còn thì focus trả về nút hủy. Khi lần nạp đầu lỗi (QĐ12) focus ở
 * "Thử lại" (nút "Quay lại danh sách đơn" vẫn dùng được), và sau khi thử lại thành công về nút "Quay lại".
 *
 * <p>Id cố định cho test: {@code orderDetailScreen}, {@code orderTitle}, {@code orderBanner}, {@code orderDate},
 * {@code orderStatus}, {@code orderReceiver}, {@code orderPhone}, {@code orderAddress}, {@code orderNote},
 * {@code orderPayment}, {@code orderLines} (bảng), {@code orderTotal}, {@code backToOrders}, {@code cancelOrder},
 * {@code orderLoading}, {@code orderDetailLoadError}, {@code orderDetailRetry}.
 */
final class OrderDetailScreen extends ScrollPane {

    static final String BACK_TEXT = "Quay lại danh sách đơn";
    static final String CANCEL_TEXT = "Hủy đơn hàng";
    static final String DATE_LABEL = "Ngày đặt";
    static final String STATUS_LABEL = "Trạng thái";
    static final String PAYMENT_LABEL = "Phương thức thanh toán";
    static final String RECEIVER_SECTION = "Thông tin nhận hàng";
    static final String RECEIVER_LABEL = "Họ tên người nhận";
    static final String PHONE_LABEL = "Số điện thoại";
    static final String ADDRESS_LABEL = "Địa chỉ giao hàng";
    static final String NOTE_LABEL = "Ghi chú";
    static final String LINES_LABEL = "Sản phẩm trong đơn hàng";
    static final String COLUMN_PRODUCT = "Sản phẩm";
    static final String COLUMN_UNIT_PRICE = "Đơn giá";
    static final String COLUMN_QUANTITY = "Số lượng";
    static final String COLUMN_LINE_TOTAL = "Thành tiền";
    static final String TOTAL_LABEL = "Tổng cộng";

    private static final double KEY_WIDTH = 200;
    private static final double UNIT_WIDTH = 130;
    private static final double QUANTITY_WIDTH = 110;
    private static final double LINE_TOTAL_WIDTH = 140;
    private static final double PRODUCT_MIN_WIDTH = 120;

    private static final List<ColumnConstraints> LINE_COLUMNS = List.of(Tables.growingColumn(PRODUCT_MIN_WIDTH),
            Tables.fixedColumn(UNIT_WIDTH), Tables.fixedColumn(QUANTITY_WIDTH), Tables.fixedColumn(LINE_TOTAL_WIDTH));

    private final OrderDetailPresenter presenter;
    private final VBox loadError;

    private final Button back = new Button(BACK_TEXT);
    private final Button cancel = new Button(CANCEL_TEXT);
    private final HBox statusHolder = new HBox();
    private final Label receiver = valueLabel("orderReceiver");
    private final Label phone = valueLabel("orderPhone");
    private final Label address = valueLabel("orderAddress");
    private final Label note = valueLabel("orderNote");
    private final Label noteKey = keyLabel(NOTE_LABEL);
    private final Label payment = valueLabel("orderPayment");
    private final VBox lines = new VBox();
    private boolean pendingFocus = true;
    private boolean cancelHadFocus;

    OrderDetailScreen(OrderDetailPresenter presenter) {
        this.presenter = presenter;

        setId("orderDetailScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        back.setId("backToOrders");
        back.getStyleClass().addAll("button-secondary", "pill");
        back.setOnAction(event -> presenter.back());

        Label title = new Label();
        title.setId("orderTitle");
        title.getStyleClass().add("page-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        title.textProperty().bind(presenter.titleTextProperty());

        Label banner = buildBanner();
        HBox loadingBox = buildLoading();

        loadError = LoadError.create("orderDetail", presenter::retry);
        Tables.showWhen(loadError, presenter.loadFailedProperty());

        VBox infoCard = buildInfoCard();
        VBox linesCard = buildLinesCard();
        Tables.showWhen(infoCard, presenter.orderProperty().isNotNull());
        Tables.showWhen(linesCard, presenter.orderProperty().isNotNull());

        VBox page = new VBox(back, title, banner, loadingBox, loadError, infoCard, linesCard);
        page.getStyleClass().add("page-root");
        setContent(page);

        presenter.orderProperty().addListener((observable, oldView, view) -> render());
        presenter.loadFailedProperty().addListener((observable, wasFailed, isFailed) -> {
            if (isFailed) {
                Platform.runLater(this::tryFocus);
            }
        });
        presenter.lines().addListener((ListChangeListener<LineRow>) change -> renderLines());
        // Nút hủy biến mất (đơn đã hủy): JavaFX đẩy focus đi chỗ khác, nên đưa về "Quay lại danh sách đơn"
        presenter.cancellableProperty().addListener((observable, was, now) -> {
            if (!now && cancelHadFocus) {
                cancelHadFocus = false;
                focusBack();
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
            }
        });

        render();
    }

    // ------------------------------------------------------------------ dựng node

    private Label buildBanner() {
        SVGPath tick = new SVGPath();
        tick.setContent("M4 11 L9 16 L20 5");
        tick.getStyleClass().add("form-notice-icon");
        Label banner = new Label();
        banner.setId("orderBanner");
        banner.getStyleClass().addAll("form-notice", "form-notice-success", "info-card");
        banner.setGraphic(tick);
        banner.setMinHeight(Region.USE_PREF_SIZE);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.textProperty().bind(presenter.bannerTextProperty());
        banner.accessibleTextProperty().bind(presenter.bannerTextProperty());
        banner.visibleProperty().bind(presenter.bannerTextProperty().isNotEmpty());
        banner.managedProperty().bind(banner.visibleProperty());
        return banner;
    }

    private HBox buildLoading() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("orderLoading");
        HBox box = new HBox(indicator);
        box.getStyleClass().add("loading");
        ObservableBooleanValue waiting = presenter.loadingProperty().and(presenter.orderProperty().isNull());
        Tables.showWhen(box, waiting);
        Tables.showWhen(indicator, waiting);
        return box;
    }

    /** Thẻ thông tin: ngày đặt, trạng thái, phương thức thanh toán, rồi khối "Thông tin nhận hàng". */
    private VBox buildInfoCard() {
        Label date = valueLabel("orderDate");
        date.textProperty().bind(presenter.dateTextProperty());
        statusHolder.setAlignment(Pos.CENTER_LEFT);

        GridPane meta = infoList();
        addRow(meta, 0, keyLabel(DATE_LABEL), date);
        addRow(meta, 1, keyLabel(STATUS_LABEL), statusHolder);
        addRow(meta, 2, keyLabel(PAYMENT_LABEL), payment);

        Label section = new Label(RECEIVER_SECTION);
        section.getStyleClass().addAll("section-title", "info-section");

        GridPane receiverList = infoList();
        addRow(receiverList, 0, keyLabel(RECEIVER_LABEL), receiver);
        addRow(receiverList, 1, keyLabel(PHONE_LABEL), phone);
        addRow(receiverList, 2, keyLabel(ADDRESS_LABEL), address);
        addRow(receiverList, 3, noteKey, note);

        VBox card = new VBox(meta, section, receiverList);
        card.getStyleClass().add("summary-card");
        return card;
    }

    /** Thẻ dòng hàng: bảng, "Tổng cộng", và nút "Hủy đơn hàng" khi hủy được. */
    private VBox buildLinesCard() {
        lines.setId("orderLines");
        lines.setAccessibleText(LINES_LABEL);

        Label totalKey = new Label(TOTAL_LABEL);
        totalKey.getStyleClass().add("section-title");
        Label total = new Label();
        total.setId("orderTotal");
        total.getStyleClass().addAll("price", "text-h2");
        total.textProperty().bind(presenter.totalTextProperty());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox totalRow = new HBox(totalKey, spacer, total);
        totalRow.setAlignment(Pos.CENTER_LEFT);
        totalRow.getStyleClass().add("table-total-row");

        cancel.setId("cancelOrder");
        cancel.getStyleClass().addAll("button-danger", "pill");
        // Không khóa nút trong lúc hủy: nút bị khóa làm focus nhảy đi; presenter tự bỏ qua khi đang gửi
        cancel.setOnAction(event -> {
            cancelHadFocus = true;
            presenter.requestCancel();
        });
        HBox actions = new HBox(cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Tables.showWhen(actions, presenter.cancellableProperty());

        VBox card = new VBox(lines, totalRow, actions);
        card.getStyleClass().add("summary-card");
        return card;
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

    /** Điền các trường của đơn hiện tại (ngày, tổng và dòng hàng đã gắn qua presenter). */
    private void render() {
        OrderDetailView view = presenter.orderProperty().get();
        if (view == null) {
            return;
        }
        statusHolder.getChildren().setAll(statusPillWithId(view));
        payment.setText(view.paymentMethodLabel());
        receiver.setText(view.receiverName());
        phone.setText(view.phone());
        address.setText(view.shippingAddress());
        boolean hasNote = view.note() != null && !view.note().isBlank();
        note.setText(hasNote ? view.note() : "");
        for (Node node : new Node[] {note, noteKey}) {
            node.setVisible(hasNote);
            node.setManaged(hasNote);
        }
        renderLines();
        Platform.runLater(this::tryFocus);
    }

    private static Label statusPillWithId(OrderDetailView view) {
        Label pill = StatusPill.create(view.statusLabel(), view.statusKey());
        pill.setId("orderStatus");
        return pill;
    }

    private void renderLines() {
        lines.getChildren().clear();
        GridPane header = Tables.headerRow(LINE_COLUMNS, COLUMN_PRODUCT, COLUMN_UNIT_PRICE, COLUMN_QUANTITY,
                COLUMN_LINE_TOTAL);
        for (Node label : header.getChildren()) {
            if (GridPane.getColumnIndex(label) != null && GridPane.getColumnIndex(label) > 0) {
                GridPane.setHalignment(label, HPos.RIGHT);   // cột số canh phải, cùng hàng dữ liệu
            }
        }
        lines.getChildren().add(header);
        for (LineRow row : presenter.lines()) {
            GridPane grid = Tables.dataRow(LINE_COLUMNS, false);
            Label product = Tables.cell(row.productName(), "text-body");
            product.setMinWidth(0);   // tên dài không có dấu cách xuống dòng thay vì đẩy tràn ngang
            product.setMaxWidth(Double.MAX_VALUE);
            Tables.put(grid, product, 0);
            String[] numbers = {row.unitPriceText(), row.quantityText(), row.lineTotalText()};
            for (int index = 0; index < numbers.length; index++) {
                Label number = Tables.cell(numbers[index], "text-body");
                Tables.put(grid, number, index + 1);
                GridPane.setHalignment(number, HPos.RIGHT);
            }
            lines.getChildren().add(grid);
        }
    }

    // ------------------------------------------------------------------ focus

    /**
     * Đưa focus về "Quay lại danh sách đơn" sau một nhịp (để việc JavaFX đẩy focus đi xong trước). Cửa sổ chưa có
     * focus (hộp xác nhận vừa đóng) thì làm thêm một lần khi cửa sổ nhận lại focus. Nút nằm ở đầu trang, nên cuộn
     * lên đầu để thấy vòng focus (nút hủy ở cuối trang, người dùng thường đang ở đó).
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
                        Platform.runLater(OrderDetailScreen.this::focusBackAtTop);
                    }
                }
            });
        }
    }

    private void focusBackAtTop() {
        back.requestFocus();
        setVvalue(getVmin());
    }

    private void tryFocus() {
        if (!pendingFocus || back.getScene() == null) {
            return;
        }
        if (presenter.loadFailedProperty().get()) {
            // Khối lỗi: focus ở "Thử lại"; pendingFocus còn đó để khi thử lại thành công focus về "Quay lại"
            Button retry = LoadError.retryButton(loadError);
            if (retry.getScene() != null && !searchFieldFocused()) {
                retry.requestFocus();
            }
            return;
        }
        if (presenter.orderProperty().get() == null) {
            return;
        }
        pendingFocus = false;
        back.requestFocus();
    }

    /** Người dùng đang gõ ở ô tìm kiếm trên thanh trên: không giành focus của họ. */
    private boolean searchFieldFocused() {
        Node owner = back.getScene().getFocusOwner();
        return owner != null && "searchField".equals(owner.getId());
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link OrderDetailRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<OrderDetailRoute> {

        private final ObjectProvider<OrderDetailPresenter> presenters;

        Factory(ObjectProvider<OrderDetailPresenter> presenters) {
            this.presenters = presenters;
        }

        @Override
        public Class<OrderDetailRoute> routeType() {
            return OrderDetailRoute.class;
        }

        @Override
        public Node create(OrderDetailRoute route) {
            OrderDetailPresenter presenter = presenters.getObject();
            OrderDetailScreen screen = new OrderDetailScreen(presenter);
            presenter.load(route);
            return screen;
        }
    }
}
