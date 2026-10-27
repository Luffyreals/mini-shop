package com.minishop.catalog;

import com.minishop.desktop.EvenRows;
import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trang chủ (QĐ5 của D3, kế hoạch tổng mục 4.2): chữ lớn "mini shop" với câu dẫn, thẻ lớn của sản phẩm mới nhất,
 * khối tối "Sắp hết hàng" (ẩn khi không có sản phẩm nào sắp hết), hàng "Hàng mới" gồm tối đa hai thẻ nhỏ và thẻ
 * "Giao hàng và thanh toán", rồi dòng cuối. Chưa có sản phẩm nào thì chỉ còn đầu trang, thẻ giao hàng và dòng cuối.
 * Chỉ dựng node và gắn vào {@link HomePresenter}; không gọi service.
 *
 * <p><b>Bố cục:</b> từ {@value CatalogScreen#MEDIUM_COLUMNS_FROM} px (cùng ngưỡng với danh sách) thẻ lớn và khối tối
 * nằm cạnh nhau (khối tối rộng cố định) và hàng "Hàng mới" có 3 cột; hẹp hơn thì khối tối xuống dưới thẻ lớn và hàng
 * có 2 cột. Thẻ giao hàng là ô cuối của hàng. Không dùng {@code GridPane} cho các hàng này, xem {@link EvenRows}.
 *
 * <p><b>Thẻ lớn và thẻ nhỏ</b> nhận focus bằng Tab; Enter hoặc bấm mở chi tiết, còn bấm nút bên trong không nổi lên
 * thẻ. Mỗi lần nạp xong nội dung được dựng lại, và focus quay về đúng điều khiển (theo id) nếu nó còn.
 *
 * <p>Id cố định cho test: {@code homeScreen}, {@code homeLead}, {@code featuredCard}, {@code featuredAdd},
 * {@code featuredDetails}, {@code lowStockBlock}, {@code lowStockRow-{id}}, {@code moreNew} ({@link EvenRows} của hàng
 * "Hàng mới": các {@code productCard-{id}} cùng {@code deliveryCard}), {@code deliveryCard}, {@code homeLoading}.
 */
final class HomeScreen extends ScrollPane {

    static final String TITLE = "mini shop";
    static final String FEATURED_LABEL = "Mới nhất";
    static final String ADD_TEXT = "Thêm vào giỏ";
    static final String DETAILS_TEXT = "Xem chi tiết";
    static final String SOLD_OUT_TEXT = "Hết hàng";
    static final String LOW_STOCK_TITLE = "Sắp hết hàng";
    static final String NEW_ARRIVALS_TITLE = "Hàng mới";
    static final String DELIVERY_TITLE = "Giao hàng và thanh toán";
    static final String FOOTER_TEXT = "Dự án học tập, không bán hàng thật.";

    /** Các dòng của thẻ giao hàng: nhãn và nội dung (spec 005, câu hỏi 4 của D3). */
    static final List<String[]> DELIVERY_ROWS = List.of(
            new String[] {"Thanh toán", "tiền mặt khi nhận"},
            new String[] {"Giao tới", "địa chỉ bạn nhập khi đặt"},
            new String[] {"Hủy đơn", "được, khi còn Chờ xử lý"});

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static final double LOW_STOCK_WIDTH = 300;
    private static final double FEATURED_IMAGE_WIDTH = 220;

    private final HomePresenter presenter;
    private final ImageLoader images;

    private final VBox page = new VBox();
    private final VBox topRow = new VBox();
    private final HBox topWide = new HBox();
    private final EvenRows cardRow = new EvenRows();
    private final VBox lowStockBlock = new VBox();
    private final VBox lowStockList = new VBox();
    private final HBox featuredHolder = new HBox();
    private final VBox deliveryCard = new VBox();
    private final List<Node> newCards = new ArrayList<>();
    private double width;

    HomeScreen(HomePresenter presenter, ImageLoader images) {
        this.presenter = presenter;
        this.images = images;

        setId("homeScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        VBox header = buildHeader();
        HBox loadingBox = buildLoading();
        buildTopRow();
        Label newTitle = new Label(NEW_ARRIVALS_TITLE);
        newTitle.getStyleClass().add("section-title");
        newTitle.setAccessibleRole(AccessibleRole.TEXT);
        show(newTitle, presenter.loadedProperty().and(Bindings.isNotEmpty(presenter.moreNew())));
        buildDeliveryCard();
        cardRow.setId("moreNew");
        show(cardRow, presenter.loadedProperty());

        Label footer = new Label(FOOTER_TEXT);
        footer.getStyleClass().add("page-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        show(footer, presenter.loadedProperty());

        page.getChildren().addAll(header, loadingBox, topRow, newTitle, cardRow, footer);
        page.getStyleClass().add("page-root");
        setContent(page);

        widthProperty().addListener((observable, oldWidth, newWidth) -> updateLayout(newWidth.doubleValue()));
        presenter.contentVersionProperty().addListener((observable, oldValue, newValue) -> render());
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            }
        });

        render();
    }

    // ------------------------------------------------------------------ dựng khung

    private VBox buildHeader() {
        Label hero = new Label(TITLE);
        hero.getStyleClass().add("home-hero");
        hero.setAccessibleRole(AccessibleRole.TEXT);

        Label lead = new Label();
        lead.setId("homeLead");
        lead.getStyleClass().addAll("home-lead", "text-body", "text-muted");
        lead.setWrapText(true);
        lead.textProperty().bind(presenter.leadTextProperty());
        show(lead, presenter.loadedProperty());

        VBox header = new VBox(hero, lead);
        header.getStyleClass().add("home-header");
        return header;
    }

    private HBox buildLoading() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("homeLoading");
        HBox box = new HBox(indicator);
        box.getStyleClass().add("loading");
        ObservableBooleanValue waiting = presenter.loadingProperty().and(presenter.loadedProperty().not());
        show(box, waiting);
        show(indicator, waiting);
        return box;
    }

    private void buildTopRow() {
        lowStockBlock.setId("lowStockBlock");
        lowStockBlock.setMaxHeight(Double.MAX_VALUE);
        lowStockBlock.getStyleClass().addAll("dark-block", "home-low-stock");

        Label title = new Label(LOW_STOCK_TITLE);
        title.getStyleClass().add("section-title");
        title.setAccessibleRole(AccessibleRole.TEXT);
        lowStockList.getStyleClass().add("home-low-stock-list");
        lowStockBlock.getChildren().addAll(title, lowStockList);
        show(lowStockBlock, presenter.hasLowStockProperty());

        featuredHolder.getStyleClass().add("home-feature-holder");
        show(featuredHolder, presenter.featuredProperty().isNotNull());
        HBox.setHgrow(featuredHolder, Priority.ALWAYS);

        topWide.getStyleClass().add("home-top-row");
        topRow.getStyleClass().add("home-top-row");
        topRow.visibleProperty().bind(presenter.featuredProperty().isNotNull().or(presenter.hasLowStockProperty()));
        topRow.managedProperty().bind(topRow.visibleProperty());
    }

    private void buildDeliveryCard() {
        deliveryCard.setId("deliveryCard");
        deliveryCard.setMaxHeight(Double.MAX_VALUE);
        deliveryCard.getStyleClass().addAll("info-card", "home-delivery");
        Label title = new Label(DELIVERY_TITLE);
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        title.setAccessibleRole(AccessibleRole.TEXT);
        deliveryCard.getChildren().add(title);
        for (String[] row : DELIVERY_ROWS) {
            Text key = new Text(row[0] + ": ");
            key.getStyleClass().add("info-key");
            Text value = new Text(row[1]);
            value.getStyleClass().add("info-value");
            TextFlow flow = new TextFlow(key, value);
            flow.setAccessibleText(row[0] + ": " + row[1]);
            deliveryCard.getChildren().add(flow);
        }
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    /** Dựng lại thẻ lớn, khối "Sắp hết hàng" và hàng "Hàng mới" từ trạng thái hiện tại của presenter. */
    private void render() {
        String focusedId = focusedId();

        featuredHolder.getChildren().clear();
        ProductDetailView featured = presenter.featuredProperty().get();
        if (featured != null) {
            HBox featuredCard = buildFeatured(featured);
            HBox.setHgrow(featuredCard, Priority.ALWAYS);
            featuredHolder.getChildren().add(featuredCard);
        }

        lowStockList.getChildren().clear();
        for (LowStockCardView row : presenter.lowStock()) {
            lowStockList.getChildren().add(buildLowStockRow(row));
        }

        newCards.clear();
        for (ProductCardView view : presenter.moreNew()) {
            CatalogScreen.ProductCard card = new CatalogScreen.ProductCard(view, images, presenter.busyProducts(),
                    presenter::openDetail, presenter::add);
            newCards.add(card);
        }
        newCards.add(deliveryCard);
        cardRow.getChildren().setAll(newCards);
        placeAll();
        restoreFocus(focusedId);
    }

    private HBox buildFeatured(ProductDetailView view) {
        long id = view.id();

        ImageTile image = ImageTile.flexible(images, 480, 600);
        image.show(view.imageUrl(), view.name());

        Label badge = new Label(FEATURED_LABEL);
        badge.getStyleClass().addAll("home-feature-badge", "text-caption");

        Label category = new Label(view.categoryName().toUpperCase(VIETNAMESE));
        category.getStyleClass().add("home-feature-category");

        Label name = new Label(view.name());
        name.getStyleClass().add("page-title");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);

        String text = view.description() == null ? "" : view.description().strip();
        Label description = new Label(text);
        description.getStyleClass().addAll("text-body", "text-muted");
        description.setWrapText(true);
        description.setMaxWidth(Double.MAX_VALUE);
        description.setVisible(!text.isEmpty());
        description.setManaged(!text.isEmpty());

        Label price = new Label();
        price.getStyleClass().addAll("price", "text-h2");
        price.textProperty().bind(presenter.featuredPriceTextProperty());

        FlowPane actions = new FlowPane();
        actions.getStyleClass().add("home-feature-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setRowValignment(VPos.CENTER);
        if (view.inStock()) {
            actions.getChildren().add(featuredAddButton(id, view.name()));
        } else {
            Label soldOut = new Label(SOLD_OUT_TEXT);
            soldOut.setId("featuredSoldOut");
            soldOut.getStyleClass().addAll("stock-label", "stock-out");
            Region dot = new Region();
            dot.getStyleClass().add("stock-dot");
            soldOut.setGraphic(dot);
            actions.getChildren().add(soldOut);
        }
        Button details = new Button(DETAILS_TEXT);
        details.setId("featuredDetails");
        details.getStyleClass().addAll("button-secondary", "pill");
        details.setAccessibleText(DETAILS_TEXT + ", " + view.name());
        details.setOnAction(event -> presenter.openDetail(id));
        details.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        actions.getChildren().add(details);

        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);
        VBox info = new VBox(badge, category, name, description, price, filler, actions);
        info.getStyleClass().add("home-feature-info");
        info.setMinWidth(0);

        HBox card = new HBox(image, info);
        card.setId("featuredCard");
        card.getStyleClass().addAll("home-feature", "home-feature-grid");
        card.setFocusTraversable(true);
        card.setAccessibleText(FEATURED_LABEL + ": " + view.name() + ", " + presenter.featuredPriceTextProperty().get()
                + (view.inStock() ? "" : ", " + SOLD_OUT_TEXT));
        card.setMaxWidth(Double.MAX_VALUE);
        // Ảnh có chiều rộng cố định; phần thông tin lấy hết chỗ còn lại. HBox (không phải GridPane) để chiều cao thẻ
        // luôn theo chiều rộng hiện tại (xem placeTop)
        image.setMinWidth(FEATURED_IMAGE_WIDTH);
        image.setPrefWidth(FEATURED_IMAGE_WIDTH);
        image.setMaxWidth(FEATURED_IMAGE_WIDTH);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        card.setOnMouseClicked(event -> {
            // Nút "+ Thêm" đang khóa không chạy handler của nó, nên lần bấm thứ hai nổi lên thẻ: bỏ qua
            if (event.getButton() == MouseButton.PRIMARY && !CatalogScreen.isInsideButton(event, card)
                    && !presenter.busyProducts().contains(id)) {
                presenter.openDetail(id);
            }
        });
        card.setOnKeyPressed(event -> {
            // Enter trên một nút bên trong nổi lên thẻ; chỉ mở chi tiết khi chính thẻ có focus
            if (event.getTarget() == card && event.getCode() == KeyCode.ENTER) {
                event.consume();
                presenter.openDetail(id);
            }
        });
        return card;
    }

    private Button featuredAddButton(long productId, String productName) {
        Button add = new Button(ADD_TEXT);
        add.setId("featuredAdd");
        add.getStyleClass().addAll("button-primary", "pill");
        add.setAccessibleText(ADD_TEXT + ", " + productName);
        add.disableProperty().bind(Bindings.createBooleanBinding(
                () -> presenter.busyProducts().contains(productId), presenter.busyProducts()));
        // Nút bị khóa trong lúc thêm; JavaFX chuyển focus đi khi nút đang giữ focus bị khóa, nên nhớ rồi trả lại
        boolean[] hadFocus = {false};
        add.setOnAction(event -> {
            hadFocus[0] = add.isFocused();
            presenter.add(productId);
        });
        add.disabledProperty().addListener((observable, wasDisabled, isDisabled) -> {
            if (!isDisabled && hadFocus[0]) {
                hadFocus[0] = false;
                Platform.runLater(add::requestFocus);
            }
        });
        // Bấm nút không được mở chi tiết: chặn sự kiện bấm nổi lên thẻ
        add.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        return add;
    }

    /** Một dòng của khối tối: tên và "Chỉ còn {n} sản phẩm"; Enter, Space hoặc bấm mở chi tiết. */
    private Node buildLowStockRow(LowStockCardView view) {
        long id = view.id();
        String count = "Chỉ còn " + view.stock() + " sản phẩm";

        Label name = new Label(view.name());
        name.getStyleClass().addAll("low-stock-name", "text-body");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        Label stock = new Label(count);
        stock.getStyleClass().addAll("text-caption", "text-muted");

        VBox row = new VBox(name, stock);
        row.setId("lowStockRow-" + id);
        row.getStyleClass().add("low-stock-row");
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.BUTTON);
        row.setAccessibleText(view.name() + ", " + count);
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                presenter.openDetail(id);
            }
        });
        row.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                event.consume();
                presenter.openDetail(id);
            }
        });
        return row;
    }

    // ------------------------------------------------------------------ bố cục

    private void updateLayout(double newWidth) {
        if (newWidth <= 0) {
            return;
        }
        double previousWidth = width;
        width = newWidth;
        if (columnsFor(newWidth) != columnsFor(previousWidth)) {
            placeAll();
        }
    }

    /** 3 cột từ {@value CatalogScreen#MEDIUM_COLUMNS_FROM} px, nhỏ hơn thì 2 (cùng ngưỡng với danh sách). */
    static int columnsFor(double width) {
        return width >= CatalogScreen.MEDIUM_COLUMNS_FROM ? 3 : 2;
    }

    private void placeAll() {
        int columns = Math.max(columnsFor(width), 2);
        placeTop(columns == 3 && presenter.hasLowStockProperty().get());
        placeCards(columns);
    }

    /**
     * Thẻ lớn và khối tối: cạnh nhau khi {@code sideBySide} (khối tối rộng {@value #LOW_STOCK_WIDTH} px), không thì
     * xếp chồng. Dùng {@code HBox}/{@code VBox} chứ không dùng {@code GridPane}: {@code GridPane} giữ lại chiều cao
     * hàng đã tính ở chiều rộng cũ, mà chiều cao thẻ ở đây phụ thuộc chiều rộng (ảnh 4:5, chữ xuống dòng), nên
     * hàng thấp hơn thẻ và các thẻ tràn lên che tiêu đề.
     */
    private void placeTop(boolean sideBySide) {
        topWide.getChildren().clear();
        topRow.getChildren().clear();
        if (sideBySide) {
            featuredHolder.setMinWidth(0);
            HBox.setHgrow(featuredHolder, Priority.ALWAYS);
            lowStockBlock.setMinWidth(LOW_STOCK_WIDTH);
            lowStockBlock.setPrefWidth(LOW_STOCK_WIDTH);
            lowStockBlock.setMaxWidth(LOW_STOCK_WIDTH);
            topWide.getChildren().addAll(featuredHolder, lowStockBlock);
            topRow.getChildren().add(topWide);
        } else {
            lowStockBlock.setMinWidth(Region.USE_COMPUTED_SIZE);
            lowStockBlock.setPrefWidth(Region.USE_COMPUTED_SIZE);
            lowStockBlock.setMaxWidth(Double.MAX_VALUE);
            topRow.getChildren().addAll(featuredHolder, lowStockBlock);
        }
    }

    /** Thẻ nhỏ rồi thẻ giao hàng vào các hàng {@code count} thẻ chia đều chiều rộng (xem {@link EvenRows}). */
    private void placeCards(int count) {
        cardRow.setColumns(count);
    }

    // ------------------------------------------------------------------ focus

    /** Id của điều khiển trong màn đang giữ focus, {@code null} nếu focus ở nơi khác. */
    private String focusedId() {
        if (getScene() == null) {
            return null;
        }
        Node owner = getScene().getFocusOwner();
        for (Node node = owner; node != null; node = node.getParent()) {
            if (node == page) {
                return owner.getId();
            }
        }
        return null;
    }

    /**
     * Sau khi dựng lại (nạp lại vì hết hàng), trả focus cho điều khiển cùng id nếu nó còn; "Thêm vào giỏ" của thẻ
     * lớn biến mất thì focus về "Xem chi tiết". Đợi một nhịp để các node mới đã vào {@code Scene}.
     */
    private void restoreFocus(String id) {
        if (id == null) {
            return;
        }
        Platform.runLater(() -> {
            Node target = page.lookup("#" + id);
            if (target == null && "featuredAdd".equals(id)) {
                target = page.lookup("#featuredDetails");
            }
            if (target != null && target.getScene() != null) {
                target.requestFocus();
            }
        });
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link HomeRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<HomeRoute> {

        private final ObjectProvider<HomePresenter> presenters;
        private final ImageLoader images;

        Factory(ObjectProvider<HomePresenter> presenters, ImageLoader images) {
            this.presenters = presenters;
            this.images = images;
        }

        @Override
        public Class<HomeRoute> routeType() {
            return HomeRoute.class;
        }

        @Override
        public Node create(HomeRoute route) {
            HomePresenter presenter = presenters.getObject();
            HomeScreen screen = new HomeScreen(presenter, images);
            presenter.load();
            return screen;
        }
    }
}
