package com.minishop.catalog;

import com.minishop.common.VndFormatter;
import com.minishop.desktop.EvenRows;
import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.Navigator;
import com.minishop.desktop.PageBar;
import com.minishop.desktop.Route;
import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ObservableSet;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Màn danh sách sản phẩm (spec 002, QĐ3, QĐ4 của D3): tiêu đề, pill danh mục, ô sắp xếp, "Tìm thấy N sản phẩm",
 * lưới thẻ, thanh phân trang, trạng thái rỗng và chân trang, cuộn dọc trong một {@link ScrollPane}.
 * Chỉ dựng node và gắn vào {@link CatalogPresenter}; không gọi service.
 *
 * <p><b>Lưới:</b> 4 cột khi chiều rộng màn từ {@value #WIDE_COLUMNS_FROM} px, 3 cột từ {@value #MEDIUM_COLUMNS_FROM}
 * px, nhỏ hơn thì 2 cột. {@link EvenRows} chia đều chiều rộng nên thẻ luôn bằng nhau (hàng cuối ít thẻ vẫn giữ
 * cỡ thẻ) và chiều cao hàng theo chiều rộng hiện tại; ô ảnh của thẻ co giãn theo tỉ lệ 4:5. Đổi số cột chỉ đổi vị
 * trí của các thẻ đang có, không dựng lại.
 *
 * <p><b>Focus khi dựng lại (QĐ3):</b> mỗi điều hướng dựng một màn mới, nên màn nhận {@link FocusTarget} do
 * {@link Factory} so route mới với route trước: chỉ đổi trang thì focus thẻ đầu tiên, đổi danh mục hoặc sắp xếp
 * thì focus lại đúng điều khiển đó. Focus được đặt một lần, sau lần nạp thành công đầu tiên.
 *
 * <p>Id cố định cho test: {@code catalogScreen}, {@code catalogTitle}, {@code categoryPills},
 * {@code categoryPill-all}, {@code categoryPill-{id}}, {@code sortBox}, {@code resultCount}, {@code productGrid},
 * {@code productCard-{id}}, {@code addButton-{id}}, {@code pageBar}, {@code emptyState}, {@code clearFilters},
 * {@code catalogLoading}.
 */
final class CatalogScreen extends ScrollPane {

    static final String TITLE = "Sản phẩm";
    static final String CATEGORIES_LABEL = "Danh mục";
    static final String SORT_LABEL = "Sắp xếp";
    static final String EMPTY_TEXT = "Không tìm thấy sản phẩm phù hợp.";
    static final String CLEAR_FILTERS_TEXT = "Xóa bộ lọc";
    static final String FOOTER_TEXT = "Dự án học tập, không bán hàng thật.";
    static final String SOLD_OUT_TEXT = "Hết hàng";
    static final String ADD_TEXT = "+ Thêm";
    static final String PAGINATION_LABEL = "Phân trang";

    static final int WIDE_COLUMNS_FROM = 1200;
    static final int MEDIUM_COLUMNS_FROM = 860;

    /** Điều khiển nhận focus sau lần nạp đầu tiên. */
    enum FocusTarget { NONE, FIRST_CARD, CATEGORY, SORT }

    /** Một lựa chọn của ô "Sắp xếp". */
    record SortOption(String key, String label) {
    }

    static final List<SortOption> SORT_OPTIONS = List.of(
            new SortOption("newest", "Mới nhất"),
            new SortOption("price_asc", "Giá: thấp đến cao"),
            new SortOption("price_desc", "Giá: cao đến thấp"));

    private final CatalogPresenter presenter;
    private final ImageLoader images;

    private final FlowPane pills = new FlowPane();
    private final ComboBox<SortOption> sortBox = new ComboBox<>();
    private final EvenRows grid = new EvenRows();
    private final PageBar pageBar = new PageBar(PAGINATION_LABEL);
    private final List<ProductCard> cardNodes = new ArrayList<>();
    private ToggleButton selectedPill;
    private int columns;
    private FocusTarget pendingFocus;

    CatalogScreen(CatalogPresenter presenter, ImageLoader images, FocusTarget focus) {
        this.presenter = presenter;
        this.images = images;
        this.pendingFocus = focus == null ? FocusTarget.NONE : focus;

        setId("catalogScreen");
        getStyleClass().add("screen-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);

        Label title = new Label(TITLE);
        title.setId("catalogTitle");
        title.getStyleClass().add("page-title");

        pills.setId("categoryPills");
        pills.getStyleClass().add("filter-row");
        pills.setAccessibleRole(AccessibleRole.TOOL_BAR);
        pills.setAccessibleText(CATEGORIES_LABEL);

        HBox toolbar = buildToolbar();
        HBox loadingBox = buildLoading();
        buildGrid();
        VBox emptyState = buildEmptyState();

        Label footer = new Label(FOOTER_TEXT);
        footer.getStyleClass().add("page-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);

        VBox page = new VBox(title, pills, toolbar, loadingBox, grid, pageBar, emptyState, footer);
        page.getStyleClass().add("page-root");
        setContent(page);

        ObservableBooleanValue hasList = presenter.loadedProperty().and(presenter.emptyProperty().not());
        show(grid, hasList);
        show(emptyState, presenter.loadedProperty().and(presenter.emptyProperty()));

        widthProperty().addListener((observable, oldWidth, newWidth) -> updateColumns(newWidth.doubleValue()));
        presenter.contentVersionProperty().addListener((observable, oldValue, newValue) -> render());
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            }
        });

        render();
    }

    private HBox buildToolbar() {
        Label sortLabel = new Label(SORT_LABEL);
        sortLabel.getStyleClass().add("field-label");
        sortLabel.setLabelFor(sortBox);

        sortBox.setId("sortBox");
        sortBox.getStyleClass().add("sort-box");
        sortBox.setAccessibleText(SORT_LABEL);
        sortBox.getItems().setAll(SORT_OPTIONS);
        sortBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SortOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public SortOption fromString(String text) {
                return null;
            }
        });
        sortBox.setValue(sortOption(presenter.sortKeyProperty().get()));
        // Gắn sau khi đã chọn giá trị ban đầu: chỉ lựa chọn của người dùng mới điều hướng
        sortBox.valueProperty().addListener((observable, oldOption, newOption) -> {
            if (newOption != null) {
                presenter.selectSort(newOption.key());
            }
        });

        Label resultCount = new Label();
        resultCount.setId("resultCount");
        resultCount.getStyleClass().addAll("text-body", "text-muted");
        resultCount.textProperty().bind(presenter.resultTextProperty());
        show(resultCount, presenter.loadedProperty());

        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);
        HBox toolbar = new HBox(sortLabel, sortBox, filler, resultCount);
        toolbar.getStyleClass().add("toolbar-row");
        return toolbar;
    }

    private HBox buildLoading() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId("catalogLoading");
        HBox box = new HBox(indicator);
        box.getStyleClass().add("loading");
        ObservableBooleanValue waiting = presenter.loadingProperty().and(presenter.loadedProperty().not());
        show(box, waiting);
        show(indicator, waiting);
        return box;
    }

    private void buildGrid() {
        grid.setId("productGrid");
        grid.setFillLastRow(false);
        pageBar.setId("pageBar");
    }

    private VBox buildEmptyState() {
        Label message = new Label(EMPTY_TEXT);
        message.getStyleClass().add("section-title");
        message.setWrapText(true);
        message.setAlignment(Pos.CENTER);
        Button clear = new Button(CLEAR_FILTERS_TEXT);
        clear.setId("clearFilters");
        clear.getStyleClass().addAll("button-primary", "pill");
        clear.setOnAction(event -> presenter.clearFilters());
        VBox box = new VBox(message, clear);
        box.setId("emptyState");
        box.getStyleClass().add("empty-state");
        return box;
    }

    /** Gắn {@code visible} và {@code managed} của {@code node} vào {@code condition}. */
    private static void show(Node node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ------------------------------------------------------------------ dựng lại theo dữ liệu

    /** Dựng lại pill, thẻ và thanh phân trang từ trạng thái hiện tại của presenter. */
    private void render() {
        Long focusedProduct = focusedProductId();

        rebuildPills();
        cardNodes.clear();
        grid.getChildren().clear();
        for (ProductCardView view : presenter.cards()) {
            ProductCard card = new ProductCard(view, images, presenter.busyProducts(),
                    presenter::openDetail, presenter::add);
            cardNodes.add(card);
            grid.getChildren().add(card);
        }
        placeCards(Math.max(columns, 2));
        pageBar.show(presenter.emptyProperty().get() ? null : presenter.pageNavProperty().get(),
                presenter::goToPage);

        if (pendingFocus == FocusTarget.NONE && focusedProduct != null) {
            restoreCardFocus(focusedProduct);
        }
        tryFocus();
    }

    private void rebuildPills() {
        pills.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        // Bấm lại pill đang chọn không được bỏ chọn nó
        group.selectedToggleProperty().addListener((observable, previous, current) -> {
            if (current == null && previous != null) {
                previous.setSelected(true);
            }
        });
        selectedPill = null;
        addPill(group, "categoryPill-all", "Tất cả danh mục", null);
        for (CategoryOption category : presenter.categories()) {
            addPill(group, "categoryPill-" + category.id(), category.name(), category.id());
        }
    }

    private void addPill(ToggleGroup group, String id, String label, Long categoryId) {
        ToggleButton pill = new ToggleButton(label);
        pill.setId(id);
        pill.getStyleClass().addAll("filter-pill", "pill");
        pill.setToggleGroup(group);
        boolean selected = Objects.equals(categoryId, presenter.selectedCategoryIdProperty().get());
        pill.setSelected(selected);
        if (selected) {
            selectedPill = pill;
        }
        pill.setOnAction(event -> presenter.selectCategory(categoryId));
        pills.getChildren().add(pill);
    }

    private static SortOption sortOption(String key) {
        return SORT_OPTIONS.stream().filter(option -> option.key().equals(key)).findFirst()
                .orElse(SORT_OPTIONS.get(0));
    }

    // ------------------------------------------------------------------ lưới 4/3/2 cột

    /** Số cột theo chiều rộng của màn (px): 4 từ 1200, 3 từ 860, nhỏ hơn thì 2. */
    static int columnsFor(double width) {
        if (width >= WIDE_COLUMNS_FROM) {
            return 4;
        }
        return width >= MEDIUM_COLUMNS_FROM ? 3 : 2;
    }

    private void updateColumns(double width) {
        if (width <= 0) {
            return;
        }
        int wanted = columnsFor(width);
        if (wanted != columns) {
            placeCards(wanted);
        }
    }

    /** Đặt các thẻ vào {@code count} cột chia đều; không tạo lại thẻ nên không mất focus. */
    private void placeCards(int count) {
        columns = count;
        grid.setColumns(count);
    }

    // ------------------------------------------------------------------ focus

    private void tryFocus() {
        if (pendingFocus == FocusTarget.NONE || !presenter.loadedProperty().get()) {
            return;
        }
        Node target = switch (pendingFocus) {
            case FIRST_CARD -> cardNodes.isEmpty() ? null : cardNodes.get(0);
            case CATEGORY -> selectedPill;
            case SORT -> sortBox;
            case NONE -> null;
        };
        if (target == null) {
            pendingFocus = FocusTarget.NONE;
            return;
        }
        if (target.getScene() == null) {
            // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng (sau khi chính ScrollPane đã vào Scene), nên
            // đợi chính điều khiển đích vào Scene rồi thử lại
            target.sceneProperty().addListener(new ChangeListener<Scene>() {
                @Override
                public void changed(ObservableValue<? extends Scene> observable, Scene oldScene, Scene newScene) {
                    if (newScene != null) {
                        observable.removeListener(this);
                        Platform.runLater(CatalogScreen.this::tryFocus);
                    }
                }
            });
            return;
        }
        pendingFocus = FocusTarget.NONE;
        // Đợi một nhịp để điều khiển đã có kích thước và nhận focus được
        Platform.runLater(target::requestFocus);
    }

    /** Id sản phẩm của thẻ đang giữ focus (hoặc có nút con giữ focus), {@code null} nếu không có. */
    private Long focusedProductId() {
        if (getScene() == null) {
            return null;
        }
        for (Node node = getScene().getFocusOwner(); node != null; node = node.getParent()) {
            if (node instanceof ProductCard card) {
                return card.productId();
            }
        }
        return null;
    }

    /** Sau khi dựng lại thẻ (nạp lại vì hết hàng), trả focus cho thẻ cùng sản phẩm nếu nó còn. */
    private void restoreCardFocus(long productId) {
        for (ProductCard card : cardNodes) {
            if (card.productId() == productId) {
                card.requestFocus();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ thẻ sản phẩm

    /**
     * {@code true} nếu cú bấm rơi vào một nút (hoặc phần con của nút) nằm trong {@code boundary}. Nút bị khóa không
     * chạy handler của nó và JavaFX để sự kiện nổi lên cha, nên thẻ tự kiểm tra thay vì tin vào {@code consume}.
     */
    static boolean isInsideButton(MouseEvent event, Node boundary) {
        Node node = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
        while (node != null && node != boundary) {
            if (node instanceof Button) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /**
     * Thẻ sản phẩm dùng chung cho danh sách và trang chủ (QĐ4): ô ảnh 4:5, danh mục viết hoa, tên, giá, rồi "Hết hàng"
     * khi hết hoặc "+ Thêm" khi còn. Cả thẻ nhận focus bằng Tab; Enter hoặc bấm mở chi tiết, còn bấm "+ Thêm" chỉ
     * thêm vào giỏ (sự kiện bấm của nút không nổi lên thẻ). Nút "+ Thêm" bị khóa khi sản phẩm nằm trong
     * {@code busyProducts}.
     */
    static final class ProductCard extends VBox {

        private static final VndFormatter PRICE = new VndFormatter();
        private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

        private final long productId;

        /**
         * @param busyProducts id sản phẩm đang được thêm vào giỏ (nút "+ Thêm" của chúng bị khóa)
         * @param onOpen       gọi khi mở chi tiết, nhận id sản phẩm
         * @param onAdd        gọi khi bấm "+ Thêm", nhận id sản phẩm
         */
        ProductCard(ProductCardView view, ImageLoader images, ObservableSet<Long> busyProducts, LongConsumer onOpen,
                    LongConsumer onAdd) {
            this.productId = view.id();
            getStyleClass().add("product-card");
            setId("productCard-" + productId);
            setSpacing(6);
            setFocusTraversable(true);
            setMaxWidth(Double.MAX_VALUE);
            setMaxHeight(Double.MAX_VALUE);

            String price = PRICE.format(view.price());
            setAccessibleText(view.name() + ", " + price + (view.inStock() ? "" : ", " + SOLD_OUT_TEXT));

            ImageTile tile = ImageTile.flexible(images, 360, 450);
            tile.show(view.imageUrl(), view.name());

            Label category = new Label(view.categoryName().toUpperCase(VIETNAMESE));
            category.getStyleClass().add("product-card-category");
            category.setMaxWidth(Double.MAX_VALUE);

            Label name = new Label(view.name());
            name.getStyleClass().addAll("product-card-name", "text-body");
            name.setWrapText(true);
            name.setMaxWidth(Double.MAX_VALUE);

            Region filler = new Region();
            VBox.setVgrow(filler, Priority.ALWAYS);

            Label priceLabel = new Label(price);
            priceLabel.getStyleClass().addAll("price", "text-body");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox bottom = new HBox(8, priceLabel, gap, view.inStock()
                    ? addButton(view, busyProducts, onAdd) : soldOutLabel());
            bottom.setAlignment(Pos.CENTER_LEFT);
            bottom.getStyleClass().add("product-card-footer");

            getChildren().addAll(tile, category, name, filler, bottom);

            setOnMouseClicked(event -> {
                // Nút "+ Thêm" đang khóa không chạy handler của nó, nên lần bấm thứ hai nổi lên thẻ: bỏ qua
                if (event.getButton() == MouseButton.PRIMARY && !isInsideButton(event, this)
                        && !busyProducts.contains(productId)) {
                    onOpen.accept(productId);
                }
            });
            setOnKeyPressed(event -> {
                // Enter trên nút "+ Thêm" nổi lên thẻ; chỉ mở chi tiết khi chính thẻ có focus
                if (event.getTarget() == this && event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    onOpen.accept(productId);
                }
            });
        }

        long productId() {
            return productId;
        }

        private Button addButton(ProductCardView view, ObservableSet<Long> busyProducts, LongConsumer onAdd) {
            Button add = new Button(ADD_TEXT);
            add.setId("addButton-" + productId);
            add.getStyleClass().addAll("button-primary", "button-compact", "pill");
            add.setAccessibleText("Thêm vào giỏ, " + view.name());
            add.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> busyProducts.contains(productId), busyProducts));
            // Nút bị khóa trong lúc thêm; JavaFX chuyển focus sang thẻ kế tiếp khi nút đang giữ focus bị khóa, nên
            // nhớ nó có focus và trả focus khi nút mở lại (người dùng bàn phím không bị nhảy sang thẻ khác)
            boolean[] hadFocus = {false};
            add.setOnAction(event -> {
                hadFocus[0] = add.isFocused();
                onAdd.accept(productId);
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

        private Label soldOutLabel() {
            Label soldOut = new Label(SOLD_OUT_TEXT);
            soldOut.setId("soldOut-" + productId);
            soldOut.getStyleClass().addAll("stock-label", "stock-out");
            Region dot = new Region();
            dot.getStyleClass().add("stock-dot");
            soldOut.setGraphic(dot);
            return soldOut;
        }
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link CatalogRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<CatalogRoute> {

        private final ObjectProvider<CatalogPresenter> presenters;
        private final ImageLoader images;
        private final Navigator navigator;

        Factory(ObjectProvider<CatalogPresenter> presenters, ImageLoader images, Navigator navigator) {
            this.presenters = presenters;
            this.images = images;
            this.navigator = navigator;
        }

        @Override
        public Class<CatalogRoute> routeType() {
            return CatalogRoute.class;
        }

        @Override
        public Node create(CatalogRoute route) {
            // Navigator đặt currentRoute sau khi dựng, nên lúc này nó còn là route trước
            FocusTarget focus = focusFor(navigator.currentRoute(), route);
            CatalogPresenter presenter = presenters.getObject();
            presenter.load(route);
            return new CatalogScreen(presenter, images, focus);
        }

        /**
         * Điều khiển cần focus lại khi {@code next} thay {@code previous} (QĐ3): đổi danh mục thì pill, đổi sắp
         * xếp thì ô sắp xếp, chỉ đổi trang thì thẻ đầu tiên; còn lại (từ khóa đổi, hoặc đến từ màn khác) không đổi
         * focus.
         */
        static FocusTarget focusFor(Route previous, CatalogRoute next) {
            if (!(previous instanceof CatalogRoute before)) {
                return FocusTarget.NONE;
            }
            if (!Objects.equals(before.categoryId(), next.categoryId())) {
                return FocusTarget.CATEGORY;
            }
            if (!before.sortKey().equals(next.sortKey())) {
                return FocusTarget.SORT;
            }
            if (before.page() != next.page() && Objects.equals(before.query(), next.query())) {
                return FocusTarget.FIRST_CARD;
            }
            return FocusTarget.NONE;
        }
    }
}
