package com.minishop.catalog;

import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Màn chi tiết một sản phẩm (spec 002, spec 004, QĐ3, QĐ11 của D3): ảnh lớn 4:5 bên trái; bên phải là nút "Về danh
 * sách sản phẩm", tên, danh mục, giá, nhãn kho, mô tả, và khi còn hàng thì "Số lượng" kèm "Thêm vào giỏ". Chỉ dựng
 * node và gắn vào {@link ProductDetailPresenter}; không gọi service.
 *
 * <p><b>Bố cục:</b> hai cột khi chiều rộng màn từ {@value CatalogScreen#MEDIUM_COLUMNS_FROM} px (cùng ngưỡng với
 * lưới danh sách), nhỏ hơn thì xếp chồng: ảnh rồi thông tin. Đổi bố cục chỉ đổi vị trí của hai khối đang có.
 *
 * <p><b>Ô số lượng:</b> là {@link Spinner} sửa được, nhưng presenter nhận <em>chữ thô</em> của ô nhập (liên kết hai
 * chiều với {@code quantityText}) để "abc" hay "100" tới {@code CartInput} và hiện "Số lượng không hợp lệ.". Bộ
 * chuyển đổi của {@code Spinner} mặc định hỏng với việc đó: "abc" làm {@code commitValue} ném
 * {@code NumberFormatException} (rơi vào hộp thoại lỗi chung) và khi mất focus chữ bị đổi lại thành giá trị cũ, còn
 * số quá khoảng bị kẹp về 99. Vì vậy bộ chuyển đổi ở đây không bao giờ ném và trả lại giá trị hiện tại cho chữ
 * không hợp lệ (số trong 1-99 thì nhận); {@code Spinner} không đổi giá trị nên không ghi đè chữ. Enter trong ô
 * nhập thêm vào giỏ (bắt ở bộ lọc sự kiện, trước khi {@code Spinner} commit).
 *
 * <p><b>Focus:</b> sau lần nạp đầu tiên, focus ở nút "Về danh sách sản phẩm" (không phải "Thêm vào giỏ": người vào
 * bằng Enter từ thẻ sản phẩm không vô tình thêm hàng khi giữ phím). Khi nạp lại làm sản phẩm hết hàng, khối mua bị
 * bỏ và focus quay về nút đó.
 *
 * <p>Id cố định cho test: {@code productDetailScreen}, {@code productName}, {@code productCategory},
 * {@code productPrice}, {@code productStock}, {@code productDescription}, {@code productImage},
 * {@code quantityField} (ô nhập của {@code Spinner}; chính {@code Spinner} là {@code quantitySpinner}),
 * {@code addToCart}, {@code backToList}, {@code detailLoading}.
 */
final class ProductDetailScreen extends ScrollPane {

    static final String BACK_TEXT = "Về danh sách sản phẩm";
    static final String QUANTITY_LABEL = "Số lượng";
    static final String ADD_TEXT = "Thêm vào giỏ";

    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 99;

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static final double IMAGE_MAX_WIDTH = 400;
    private static final double STACKED_IMAGE_MAX_WIDTH = 320;

    private final ProductDetailPresenter presenter;

    private final GridPane layout = new GridPane();
    private final ImageTile image;
    private final VBox info = new VBox();
    private final Button back = new Button(BACK_TEXT);
    private final Label name = new Label();
    private final Label category = new Label();
    private final Label description = new Label();
    private final VBox purchase = new VBox();
    private final Spinner<Integer> quantity = new Spinner<>();
    private final Button add = new Button(ADD_TEXT);
    private boolean stacked;
    private boolean pendingFocus = true;
    private boolean hadAddFocus;

    ProductDetailScreen(ProductDetailPresenter presenter, ImageLoader images) {
        this.presenter = presenter;
        this.image = ImageTile.flexible(images, 480, 600);

        setId("productDetailScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        image.setId("productImage");
        image.setMaxWidth(IMAGE_MAX_WIDTH);

        buildInfo();

        layout.getStyleClass().addAll("card", "detail-layout");
        layout.getChildren().addAll(image, info);
        place(false);

        VBox page = new VBox(layout);
        page.getStyleClass().add("page-root");
        setContent(page);

        widthProperty().addListener((observable, oldWidth, newWidth) -> updateLayout(newWidth.doubleValue()));
        presenter.productProperty().addListener((observable, oldView, view) -> render());
        presenter.inStockProperty().addListener((observable, wasInStock, isInStock) -> {
            if (!isInStock && purchaseHasFocus()) {
                back.requestFocus();
            }
        });
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            }
        });
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng (sau khi chính ScrollPane đã vào Scene), nên
        // theo dõi nút "Về danh sách sản phẩm" chứ không phải màn; đợi một nhịp để nút nhận focus được
        back.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::tryFocus);
            }
        });

        render();
    }

    // ------------------------------------------------------------------ dựng khối thông tin

    private void buildInfo() {
        back.setId("backToList");
        back.getStyleClass().addAll("button-secondary", "pill");
        back.setOnAction(event -> presenter.back());

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("detailLoading");
        HBox loadingBox = new HBox(indicator);
        loadingBox.getStyleClass().add("loading");
        ObservableBooleanValue waiting = presenter.loadingProperty().and(presenter.productProperty().isNull());
        show(loadingBox, waiting);
        show(indicator, waiting);

        name.setId("productName");
        name.getStyleClass().add("page-title");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);

        category.setId("productCategory");
        category.getStyleClass().add("detail-category");

        Label price = new Label();
        price.setId("productPrice");
        price.getStyleClass().addAll("price", "text-h2");
        price.textProperty().bind(presenter.priceTextProperty());

        Label stock = new Label();
        stock.setId("productStock");
        stock.getStyleClass().add("stock-label");
        Region dot = new Region();
        dot.getStyleClass().add("stock-dot");
        stock.setGraphic(dot);
        stock.textProperty().bind(presenter.stockTextProperty());
        presenter.inStockProperty().addListener((observable, oldValue, inStock) -> markStock(stock, inStock));
        markStock(stock, presenter.inStockProperty().get());

        description.setId("productDescription");
        description.getStyleClass().addAll("text-body", "detail-description");
        description.setWrapText(true);
        description.setMaxWidth(Double.MAX_VALUE);

        buildPurchase();

        VBox details = new VBox(name, category, price, stock, description, purchase);
        details.getStyleClass().add("detail-info");
        show(details, presenter.productProperty().isNotNull());

        info.getStyleClass().add("detail-info");
        info.getChildren().addAll(back, loadingBox, details);
    }

    private void buildPurchase() {
        Label label = new Label(QUANTITY_LABEL);
        label.getStyleClass().add("field-label");
        label.setLabelFor(quantity);

        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(MIN_QUANTITY, MAX_QUANTITY, MIN_QUANTITY);
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : String.valueOf(value);
            }

            @Override
            public Integer fromString(String text) {
                // Không ném và không kẹp: chữ không hợp lệ giữ nguyên giá trị, nên ô nhập giữ nguyên chữ thô
                try {
                    int parsed = Integer.parseInt(text == null ? "" : text.strip());
                    return parsed >= MIN_QUANTITY && parsed <= MAX_QUANTITY ? parsed : factory.getValue();
                } catch (NumberFormatException e) {
                    return factory.getValue();
                }
            }
        });
        quantity.setValueFactory(factory);
        quantity.setEditable(true);
        quantity.setId("quantitySpinner");
        quantity.getStyleClass().add("quantity-spinner");
        quantity.setAccessibleText(QUANTITY_LABEL);
        quantity.setPrefWidth(120);
        quantity.setMinWidth(120);

        TextField editor = quantity.getEditor();
        editor.setId("quantityField");
        editor.setAccessibleText(QUANTITY_LABEL);
        editor.textProperty().bindBidirectional(presenter.quantityTextProperty());
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                presenter.addToCart();
            }
        });

        add.setId("addToCart");
        add.getStyleClass().addAll("button-primary", "pill");
        add.disableProperty().bind(presenter.busyProperty());
        add.setOnAction(event -> {
            hadAddFocus = add.isFocused();
            presenter.addToCart();
        });
        // Nút bị khóa trong lúc thêm; JavaFX chuyển focus đi khi nút đang giữ focus bị khóa, nên nhớ rồi trả lại
        add.disabledProperty().addListener((observable, wasDisabled, isDisabled) -> {
            if (!isDisabled && hadAddFocus) {
                hadAddFocus = false;
                Platform.runLater(add::requestFocus);
            }
        });

        HBox row = new HBox(quantity, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("detail-buy-row");

        purchase.getChildren().addAll(label, row);
        purchase.getStyleClass().add("detail-purchase");
        show(purchase, presenter.inStockProperty());
    }

    private static void markStock(Label stock, boolean inStock) {
        stock.getStyleClass().removeAll("stock-in", "stock-out");
        stock.getStyleClass().add(inStock ? "stock-in" : "stock-out");
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ------------------------------------------------------------------ nội dung theo sản phẩm

    /** Điền tên, danh mục, mô tả và ảnh từ sản phẩm hiện tại (giá và kho đã gắn thẳng vào presenter). */
    private void render() {
        ProductDetailView view = presenter.productProperty().get();
        if (view == null) {
            return;
        }
        name.setText(view.name());
        category.setText(view.categoryName().toUpperCase(VIETNAMESE));
        String text = view.description() == null ? "" : view.description().strip();
        description.setText(text);
        description.setVisible(!text.isEmpty());
        description.setManaged(!text.isEmpty());
        image.show(view.imageUrl(), view.name());
        add.setAccessibleText(ADD_TEXT + ", " + view.name());
        Platform.runLater(this::tryFocus);
    }

    // ------------------------------------------------------------------ bố cục 2 cột hoặc xếp chồng

    private void updateLayout(double width) {
        if (width <= 0) {
            return;
        }
        boolean wantStacked = width < CatalogScreen.MEDIUM_COLUMNS_FROM;
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
            column.setPercentWidth(stack ? 100 : (i == 0 ? 40 : 60));
            column.setHgrow(Priority.ALWAYS);
            layout.getColumnConstraints().add(column);
        }
        image.setMaxWidth(stack ? STACKED_IMAGE_MAX_WIDTH : IMAGE_MAX_WIDTH);
        GridPane.setColumnIndex(image, 0);
        GridPane.setRowIndex(image, 0);
        GridPane.setHalignment(image, HPos.LEFT);
        GridPane.setValignment(image, VPos.TOP);
        GridPane.setFillWidth(image, true);
        GridPane.setColumnIndex(info, stack ? 0 : 1);
        GridPane.setRowIndex(info, stack ? 1 : 0);
        GridPane.setValignment(info, VPos.TOP);
        GridPane.setFillWidth(info, true);
    }

    // ------------------------------------------------------------------ focus

    private void tryFocus() {
        if (!pendingFocus || back.getScene() == null || presenter.productProperty().get() == null) {
            return;
        }
        pendingFocus = false;
        back.requestFocus();
    }

    private boolean purchaseHasFocus() {
        if (getScene() == null) {
            return false;
        }
        for (Node node = getScene().getFocusOwner(); node != null; node = node.getParent()) {
            if (node == purchase) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link ProductDetailRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<ProductDetailRoute> {

        private final ObjectProvider<ProductDetailPresenter> presenters;
        private final ImageLoader images;

        Factory(ObjectProvider<ProductDetailPresenter> presenters, ImageLoader images) {
            this.presenters = presenters;
            this.images = images;
        }

        @Override
        public Class<ProductDetailRoute> routeType() {
            return ProductDetailRoute.class;
        }

        @Override
        public Node create(ProductDetailRoute route) {
            ProductDetailPresenter presenter = presenters.getObject();
            ProductDetailScreen screen = new ProductDetailScreen(presenter, images);
            presenter.load(route);
            return screen;
        }
    }
}
