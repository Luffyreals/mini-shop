package com.minishop.admin;

import com.minishop.admin.AdminProductFormPresenter.Field;
import com.minishop.catalog.CategoryOption;
import com.minishop.desktop.ImageLoader;
import com.minishop.desktop.ImageTile;
import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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
import javafx.util.StringConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Màn "Thêm sản phẩm" / "Sửa sản phẩm" (spec 006 AC-006-18 đến -22, -25 đến -30, -32 đến -34, -37; F4 của D5). Chỉ
 * dựng node và gắn vào {@link AdminProductFormPresenter}; không gọi service. Hộp chọn file do
 * {@link ImageFileDialog} mở (trên luồng giao diện) rồi đưa file cho {@code presenter.imageChosen}.
 *
 * <p><b>Bố cục:</b> một thẻ form (tên, danh mục, giá, tồn kho ban đầu khi thêm, mô tả, "Đang bán" khi thêm, khu ảnh,
 * "Lưu" và "Hủy") nên Tab đi theo thứ tự trên màn và "Lưu" nằm sau mọi ô. Màn sửa có thêm thẻ "Điều chỉnh tồn
 * kho": từ {@value #TWO_COLUMNS_FROM_WIDTH} px bề rộng vùng màn thì nằm bên phải thẻ form, nhỏ hơn thì xếp chồng
 * dưới thẻ form. Màn thêm chỉ có thẻ form. Chưa có danh mục: chỉ câu "Cần tạo danh mục trước khi thêm sản phẩm."
 * và nút "Thêm danh mục".
 *
 * <p><b>Bàn phím:</b> Enter trong ô một dòng gửi form (trong "Số lượng thay đổi" thì cập nhật tồn kho); trong ô mô
 * tả thì xuống dòng. Tab và Shift+Tab đi qua mọi ô (ô mô tả không nuốt Tab); Enter và Space đều bấm được nút. Sau
 * khi kiểm form sai, focus tới ô lỗi đầu tiên (vùng cuộn tự cuộn tới nó); sau lần nạp đầu focus ở ô tên. Mỗi ô có
 * nhãn gắn {@code labelFor}, chữ cho trình đọc màn hình là nhãn và lỗi là phần trợ giúp của ô. Trong lúc ghi, các ô
 * chỉ bị khóa chỉnh sửa và nút vẫn nhận focus (presenter bỏ qua lần bấm thứ hai; một cờ {@code busy} cho cả màn).
 *
 * <p>Id cố định cho test: {@code productFormScreen}, {@code productFormTitle}, {@code productFormLoading},
 * {@code productFormBusy}, {@code productActiveFlag}, {@code viewInStore}, {@code hideProductButton},
 * {@code showProductButton}, {@code productNameField}, {@code productCategoryField}, {@code productPriceField},
 * {@code productInitialStockField}, {@code productDescriptionField}, {@code productActiveCheck},
 * {@code productNameError}, {@code productCategoryError}, {@code productPriceError},
 * {@code productInitialStockError}, {@code productDescriptionError}, {@code currentImage}, {@code imagePreview},
 * {@code chooseImage}, {@code clearImage}, {@code selectedImageName}, {@code imageChecking},
 * {@code productImageError}, {@code saveProduct}, {@code cancelProduct}, {@code stockCurrent},
 * {@code stockDeltaField}, {@code stockDeltaError}, {@code adjustStock}, {@code noCategories},
 * {@code addCategoryFirst}; cộng các id của thanh bên ({@link AdminScreen}).
 */
final class AdminProductFormScreen extends AdminScreen {

    static final String TITLE_NEW = "Thêm sản phẩm";
    static final String TITLE_EDIT = "Sửa sản phẩm";
    static final String NAME_LABEL = "Tên sản phẩm";
    static final String CATEGORY_LABEL = "Danh mục";
    static final String CATEGORY_PROMPT = "Chọn danh mục";
    static final String PRICE_LABEL = "Giá (₫)";
    static final String INITIAL_STOCK_LABEL = "Tồn kho ban đầu";
    static final String DESCRIPTION_LABEL = "Mô tả (không bắt buộc)";
    static final String ACTIVE_LABEL = "Đang bán";
    static final String HIDDEN_LABEL = "Đã ẩn";
    static final String IMAGE_LABEL = "Ảnh sản phẩm";
    static final String NEW_IMAGE_LABEL = "Ảnh mới (không bắt buộc)";
    static final String CURRENT_IMAGE_LABEL = "Ảnh hiện tại";
    static final String IMAGE_HINT = "JPG, PNG hoặc WebP, tối đa 2 MB.";
    static final String CHOOSE_IMAGE_TEXT = "Chọn ảnh";
    static final String CLEAR_IMAGE_TEXT = "Bỏ chọn ảnh";
    static final String SAVE_TEXT = "Lưu";
    static final String CANCEL_TEXT = "Hủy";
    static final String VIEW_IN_STORE_TEXT = "Xem trên cửa hàng";
    static final String HIDE_TEXT = "Ẩn";
    static final String SHOW_TEXT = "Hiện lại";
    static final String STOCK_TITLE = "Điều chỉnh tồn kho";
    static final String DELTA_LABEL = "Số lượng thay đổi";
    static final String DELTA_HINT = "Số dương để nhập thêm, số âm để giảm (ví dụ: 20 hoặc -3).";
    static final String ADJUST_TEXT = "Cập nhật tồn kho";
    static final String NO_CATEGORIES_TEXT = "Cần tạo danh mục trước khi thêm sản phẩm.";
    static final String ADD_CATEGORY_TEXT = "Thêm danh mục";

    /** Từ chiều rộng vùng màn này trở lên, màn sửa xếp thẻ tồn kho bên phải thẻ form. */
    static final int TWO_COLUMNS_FROM_WIDTH = TWO_COLUMNS_FROM;
    static final int FORM_MAX_WIDTH = 640;
    static final int SIDE_WIDTH = 300;

    private static final double SPINNER_SIZE = 22;
    private static final double PREVIEW_WIDTH = 96;
    private static final double PREVIEW_HEIGHT = 120;

    private final AdminProductFormPresenter presenter;
    private final ImageFileDialog fileDialog;
    private final boolean isNew;

    private final GridPane layout = new GridPane();
    private final VBox formCard = new VBox();
    private final VBox stockCard = new VBox();
    private final TextField nameField = new TextField();
    private final ComboBox<CategoryOption> categoryField = new ComboBox<>();
    private final TextField priceField = new TextField();
    private final TextField initialStockField = new TextField();
    private final TextArea descriptionField = new TextArea();
    private final CheckBox activeCheck = new CheckBox(ACTIVE_LABEL);
    private final TextField deltaField = new TextField();
    private final Button chooseImage = new Button(CHOOSE_IMAGE_TEXT);
    private final Button clearImage = new Button(CLEAR_IMAGE_TEXT);
    private final Button save = new Button(SAVE_TEXT);
    private final Button cancel = new Button(CANCEL_TEXT);
    private final Button adjust = new Button(ADJUST_TEXT);
    private final Button viewInStore = new Button(VIEW_IN_STORE_TEXT);
    private final Button hideButton = new Button(HIDE_TEXT);
    private final Button showButton = new Button(SHOW_TEXT);
    private final Button addCategoryFirst = new Button(ADD_CATEGORY_TEXT);
    private final Label activeFlag = new Label();
    private final ImageTile currentImage;
    private final ImageTile preview;

    private boolean stacked;
    private boolean pendingFocus = true;
    private boolean focusAfterToggle;

    AdminProductFormScreen(AdminProductFormPresenter presenter, ImageLoader images, ImageFileDialog fileDialog,
                           AdminNavPresenter nav, boolean isNew) {
        super(AdminSection.PRODUCTS, nav);
        this.presenter = presenter;
        this.fileDialog = fileDialog;
        this.isNew = isNew;
        this.currentImage = isNew ? null : new ImageTile(images, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        this.preview = new ImageTile(images, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        setId("productFormScreen");

        Label title = pageTitle(isNew ? TITLE_NEW : TITLE_EDIT, "productFormTitle");
        HBox head = buildHead(title);

        HBox loadingBox = loadingBox("productFormLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));

        VBox noCategories = buildNoCategories();
        buildFormCard();
        buildStockCard();
        buildLayout();
        show(layout, presenter.loadedProperty().and(presenter.noCategoriesProperty().not()));

        setBody(page(head, loadingBox, noCategories, layout));

        wirePresenter();
        bodyWidthProperty().addListener((observable, oldWidth, newWidth) -> updateLayout(newWidth.doubleValue()));
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                presenter.dispose();
            } else {
                Platform.runLater(this::tryFocus);
            }
        });
    }

    // ------------------------------------------------------------------ đầu màn

    private HBox buildHead(Label title) {
        ProgressIndicator busy = spinner("productFormBusy");
        show(busy, presenter.busyProperty());
        if (isNew) {
            return pageHead(title, busy);
        }

        Region dot = new Region();
        dot.getStyleClass().add("stock-dot");
        activeFlag.setId("productActiveFlag");
        activeFlag.setGraphic(dot);
        activeFlag.textProperty().bind(presenter.activeNowProperty().map(active -> active ? ACTIVE_LABEL : HIDDEN_LABEL));
        activeFlag.accessibleTextProperty().bind(activeFlag.textProperty());
        activeFlag.setMinWidth(Region.USE_PREF_SIZE);
        presenter.activeNowProperty().addListener((observable, wasActive, isActive) -> renderFlag(isActive));
        renderFlag(presenter.activeNowProperty().get());
        show(activeFlag, presenter.loadedProperty());

        viewInStore.setId("viewInStore");
        viewInStore.getStyleClass().addAll("button-secondary", "pill");
        viewInStore.setOnAction(event -> presenter.viewInStore());
        show(viewInStore, presenter.loadedProperty().and(presenter.activeNowProperty()));

        hideButton.setId("hideProductButton");
        hideButton.getStyleClass().addAll("button-secondary", "pill");
        hideButton.setOnAction(event -> {
            focusAfterToggle = true;
            presenter.hide();
        });
        show(hideButton, presenter.loadedProperty().and(presenter.activeNowProperty()));

        showButton.setId("showProductButton");
        showButton.getStyleClass().addAll("button-secondary", "pill");
        showButton.setOnAction(event -> {
            focusAfterToggle = true;
            presenter.show();
        });
        show(showButton, presenter.loadedProperty().and(presenter.activeNowProperty().not()));

        enterFires(viewInStore, hideButton, showButton);
        // Nút vừa bấm biến mất khi trạng thái đổi: đưa focus sang nút còn lại để không rơi về đầu màn
        presenter.activeNowProperty().addListener((observable, wasActive, isActive) -> {
            if (focusAfterToggle) {
                focusAfterToggle = false;
                Platform.runLater(() -> {
                    Button target = isActive ? hideButton : showButton;
                    if (getScene() != null && target.isVisible()) {
                        target.requestFocus();
                    }
                });
            }
        });
        return pageHead(title, busy, activeFlag, viewInStore, hideButton, showButton);
    }

    private void renderFlag(boolean active) {
        activeFlag.getStyleClass().removeAll("admin-flag-on", "admin-flag-off");
        activeFlag.getStyleClass().add(active ? "admin-flag-on" : "admin-flag-off");
    }

    private VBox buildNoCategories() {
        Label message = new Label(NO_CATEGORIES_TEXT);
        message.setId("noCategories");
        message.getStyleClass().add("text-body");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        addCategoryFirst.setId("addCategoryFirst");
        addCategoryFirst.getStyleClass().addAll("button-primary", "pill");
        addCategoryFirst.setOnAction(event -> presenter.addCategory());
        enterFires(addCategoryFirst);

        VBox card = new VBox(16, message, addCategoryFirst);
        card.getStyleClass().add("summary-card");
        card.setMaxWidth(FORM_MAX_WIDTH);
        show(card, presenter.noCategoriesProperty());
        return card;
    }

    // ------------------------------------------------------------------ thẻ form

    private void buildFormCard() {
        nameField.setId("productNameField");
        priceField.setId("productPriceField");
        initialStockField.setId("productInitialStockField");
        descriptionField.setId("productDescriptionField");
        categoryField.setId("productCategoryField");
        activeCheck.setId("productActiveCheck");

        nameField.textProperty().bindBidirectional(presenter.nameProperty());
        priceField.textProperty().bindBidirectional(presenter.priceProperty());
        initialStockField.textProperty().bindBidirectional(presenter.initialStockProperty());
        descriptionField.textProperty().bindBidirectional(presenter.descriptionProperty());
        activeCheck.selectedProperty().bindBidirectional(presenter.activeCheckedProperty());

        nameField.setOnAction(event -> presenter.save());
        priceField.setOnAction(event -> presenter.save());
        initialStockField.setOnAction(event -> presenter.save());
        descriptionField.setPrefRowCount(5);
        descriptionField.setWrapText(true);

        buildCategoryField();

        VBox nameBox = field(NAME_LABEL, nameField, "productNameError", presenter.nameErrorProperty());
        VBox categoryBox = field(CATEGORY_LABEL, categoryField, "productCategoryError",
                presenter.categoryErrorProperty());
        VBox priceBox = field(PRICE_LABEL, priceField, "productPriceError", presenter.priceErrorProperty());
        VBox descriptionBox = field(DESCRIPTION_LABEL, descriptionField, "productDescriptionError",
                presenter.descriptionErrorProperty());

        formCard.getStyleClass().add("summary-card");
        formCard.setSpacing(16);
        formCard.getChildren().addAll(nameBox, categoryBox);
        if (isNew) {
            VBox stockBox = field(INITIAL_STOCK_LABEL, initialStockField, "productInitialStockError",
                    presenter.initialStockErrorProperty());
            HBox.setHgrow(priceBox, Priority.ALWAYS);
            HBox.setHgrow(stockBox, Priority.ALWAYS);
            HBox money = new HBox(16, priceBox, stockBox);
            formCard.getChildren().add(money);
            tabThrough(descriptionField, initialStockField, activeCheck, chooseImage);
        } else {
            formCard.getChildren().add(priceBox);
            tabThrough(descriptionField, priceField, chooseImage);
        }
        formCard.getChildren().add(descriptionBox);
        if (isNew) {
            formCard.getChildren().add(activeCheck);
        }
        formCard.getChildren().addAll(buildImageField(), buildActions());
    }

    private void buildCategoryField() {
        categoryField.setPromptText(CATEGORY_PROMPT);
        categoryField.setItems(presenter.categories());
        categoryField.valueProperty().bindBidirectional(presenter.categoryProperty());
        categoryField.getStyleClass().add("form-combo");
        categoryField.setMaxWidth(Double.MAX_VALUE);
        categoryField.setConverter(new StringConverter<>() {
            @Override
            public String toString(CategoryOption option) {
                return option == null ? "" : option.name();
            }

            @Override
            public CategoryOption fromString(String text) {
                return null;
            }
        });
    }

    /**
     * Nhãn, ô nhập và dòng lỗi ngay dưới ô. Ô nhận nhãn làm chữ cho trình đọc màn hình và dòng lỗi làm phần trợ
     * giúp; ô văn bản chỉ bị khóa chỉnh sửa (không {@code disable}) khi một việc ghi đang chạy.
     */
    private VBox field(String labelText, Control input, String errorId, ReadOnlyStringProperty error) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setLabelFor(input);

        input.setAccessibleText(labelText);
        input.accessibleHelpProperty().bind(error);
        if (input instanceof TextInputControl text) {
            text.editableProperty().bind(presenter.busyProperty().not());
        }

        Label errorLabel = errorLabel(errorId, error);
        return new VBox(6, label, input, errorLabel);
    }

    private static Label errorLabel(String id, ReadOnlyStringProperty error) {
        Label errorLabel = new Label();
        errorLabel.setId(id);
        errorLabel.getStyleClass().add("field-error");
        errorLabel.setWrapText(true);
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);
        errorLabel.textProperty().bind(error);
        errorLabel.visibleProperty().bind(error.isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());
        return errorLabel;
    }

    // ------------------------------------------------------------------ khu ảnh

    private VBox buildImageField() {
        VBox box = new VBox();
        box.getStyleClass().add("admin-image-field");

        if (!isNew) {
            Label currentLabel = new Label(CURRENT_IMAGE_LABEL);
            currentLabel.getStyleClass().add("field-label");
            currentImage.setId("currentImage");
            currentImage.show(null, null);
            presenter.currentImageUrlProperty().addListener((observable, oldUrl, newUrl) -> showCurrentImage());
            showCurrentImage();
            box.getChildren().addAll(currentLabel, currentImage);
        }

        Label imageLabel = new Label(isNew ? IMAGE_LABEL : NEW_IMAGE_LABEL);
        imageLabel.getStyleClass().add("field-label");
        imageLabel.setLabelFor(chooseImage);

        preview.setId("imagePreview");
        show(preview, presenter.previewUrlProperty().isNotEmpty());
        presenter.previewUrlProperty().addListener((observable, oldUrl, newUrl) -> showPreview());
        presenter.selectedImageNameProperty().addListener((observable, oldName, newName) -> showPreview());
        showPreview();

        chooseImage.setId("chooseImage");
        chooseImage.getStyleClass().addAll("button-secondary", "pill");
        chooseImage.setAccessibleText(CHOOSE_IMAGE_TEXT);
        chooseImage.setOnAction(event -> fileDialog.choose().ifPresent(presenter::imageChosen));

        clearImage.setId("clearImage");
        clearImage.getStyleClass().addAll("button-secondary", "pill");
        clearImage.setAccessibleText(CLEAR_IMAGE_TEXT);
        clearImage.setOnAction(event -> {
            presenter.clearImage();
            chooseImage.requestFocus();   // "Bỏ chọn ảnh" biến mất: focus về nút còn lại của khu ảnh
        });
        show(clearImage, presenter.selectedImageProperty().isNotNull());
        enterFires(chooseImage, clearImage);

        ProgressIndicator checking = spinner("imageChecking");
        show(checking, presenter.checkingImageProperty());
        HBox buttons = new HBox(8, chooseImage, clearImage, checking);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label fileName = new Label();
        fileName.setId("selectedImageName");
        fileName.getStyleClass().addAll("text-body", "text-muted");
        fileName.setWrapText(true);
        fileName.setMinHeight(Region.USE_PREF_SIZE);
        fileName.textProperty().bind(presenter.selectedImageNameProperty());
        fileName.visibleProperty().bind(presenter.selectedImageNameProperty().isNotEmpty());
        fileName.managedProperty().bind(fileName.visibleProperty());

        VBox controls = new VBox(8, buttons, fileName);
        HBox.setHgrow(controls, Priority.ALWAYS);
        HBox media = new HBox(16, preview, controls);
        media.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(IMAGE_HINT);
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        Label error = errorLabel("productImageError", presenter.imageErrorProperty());
        chooseImage.accessibleHelpProperty().bind(
                Bindings.when(presenter.imageErrorProperty().isEmpty()).then(IMAGE_HINT)
                        .otherwise(presenter.imageErrorProperty()));

        box.getChildren().addAll(imageLabel, media, hint, error);
        return box;
    }

    private void showCurrentImage() {
        String url = presenter.currentImageUrlProperty().get();
        currentImage.show(url.isEmpty() ? null : url, url.isEmpty() ? null : CURRENT_IMAGE_LABEL);
    }

    private void showPreview() {
        String url = presenter.previewUrlProperty().get();
        preview.show(url.isEmpty() ? null : url, presenter.selectedImageNameProperty().get());
    }

    // ------------------------------------------------------------------ "Lưu" và "Hủy"

    private HBox buildActions() {
        save.setId("saveProduct");
        save.getStyleClass().addAll("button-primary", "pill");
        save.setOnAction(event -> presenter.save());

        cancel.setId("cancelProduct");
        cancel.getStyleClass().addAll("button-secondary", "pill");
        cancel.setOnAction(event -> presenter.cancel());
        enterFires(save, cancel);

        HBox actions = new HBox(12, save, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }

    // ------------------------------------------------------------------ thẻ tồn kho (màn sửa)

    private void buildStockCard() {
        if (isNew) {
            return;
        }
        Label title = new Label(STOCK_TITLE);
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);

        Label current = new Label();
        current.setId("stockCurrent");
        current.getStyleClass().addAll("text-body", "admin-list-name");
        current.setWrapText(true);
        current.setMinHeight(Region.USE_PREF_SIZE);
        current.textProperty().bind(presenter.stockTextProperty());

        deltaField.setId("stockDeltaField");
        deltaField.textProperty().bindBidirectional(presenter.deltaProperty());
        deltaField.setOnAction(event -> presenter.adjustStock());
        Label label = new Label(DELTA_LABEL);
        label.getStyleClass().add("field-label");
        label.setLabelFor(deltaField);
        deltaField.setAccessibleText(DELTA_LABEL);
        deltaField.accessibleHelpProperty().bind(
                Bindings.when(presenter.deltaErrorProperty().isEmpty()).then(DELTA_HINT)
                        .otherwise(presenter.deltaErrorProperty()));
        deltaField.editableProperty().bind(presenter.busyProperty().not());

        Label hint = new Label(DELTA_HINT);
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        Label error = errorLabel("stockDeltaError", presenter.deltaErrorProperty());

        adjust.setId("adjustStock");
        adjust.getStyleClass().addAll("button-primary", "pill");
        adjust.setOnAction(event -> presenter.adjustStock());
        enterFires(adjust);

        stockCard.getStyleClass().add("admin-stock-card");
        stockCard.getChildren().addAll(title, current, new VBox(6, label, deltaField, hint, error), adjust);
    }

    // ------------------------------------------------------------------ gắn presenter

    private void wirePresenter() {
        presenter.firstErrorFieldProperty().addListener((observable, oldField, field) -> {
            if (field != null) {
                nodeFor(field).requestFocus();
            }
        });
        presenter.loadedProperty().addListener((observable, wasLoaded, isLoaded) -> {
            if (isLoaded) {
                Platform.runLater(this::tryFocus);
            }
        });
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng; dữ liệu có thể về trước lúc đó, nên thử lại
        nameField.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(this::tryFocus);
            }
        });
    }

    private Node nodeFor(Field field) {
        return switch (field) {
            case NAME -> nameField;
            case CATEGORY -> categoryField;
            case PRICE -> priceField;
            case INITIAL_STOCK -> initialStockField;
            case DESCRIPTION -> descriptionField;
            case IMAGE -> chooseImage;
            case DELTA -> deltaField;
        };
    }

    // ------------------------------------------------------------------ bố cục 2 cột hoặc xếp chồng

    private void buildLayout() {
        layout.getChildren().add(formCard);
        if (!isNew) {
            layout.getChildren().add(stockCard);
        }
        layout.getStyleClass().add("admin-columns");
        place(false);
    }

    private void updateLayout(double width) {
        if (width <= 0) {
            return;
        }
        boolean wantStacked = width < TWO_COLUMNS_FROM_WIDTH;
        if (wantStacked != stacked) {
            place(wantStacked);
        }
    }

    private void place(boolean stack) {
        stacked = stack;
        layout.getColumnConstraints().clear();
        ColumnConstraints main = new ColumnConstraints(0, 0, FORM_MAX_WIDTH);
        main.setHgrow(Priority.ALWAYS);
        layout.getColumnConstraints().add(main);
        if (!isNew && !stack) {
            ColumnConstraints side = new ColumnConstraints(SIDE_WIDTH, SIDE_WIDTH, SIDE_WIDTH);
            side.setHgrow(Priority.NEVER);
            layout.getColumnConstraints().add(side);
        }
        GridPane.setColumnIndex(formCard, 0);
        GridPane.setRowIndex(formCard, 0);
        GridPane.setValignment(formCard, VPos.TOP);
        GridPane.setHalignment(formCard, HPos.LEFT);
        GridPane.setFillWidth(formCard, true);
        if (!isNew) {
            GridPane.setColumnIndex(stockCard, stack ? 0 : 1);
            GridPane.setRowIndex(stockCard, stack ? 1 : 0);
            GridPane.setValignment(stockCard, VPos.TOP);
            GridPane.setHalignment(stockCard, HPos.LEFT);
            GridPane.setFillWidth(stockCard, true);
            GridPane.setFillHeight(stockCard, false);
        }
    }

    // ------------------------------------------------------------------ focus

    /** Sau lần nạp đầu: focus vào ô tên (hoặc nút "Thêm danh mục" khi chưa có danh mục). Chỉ chạy một lần. */
    private void tryFocus() {
        if (!pendingFocus || getScene() == null || !presenter.loadedProperty().get()) {
            return;
        }
        Node target = presenter.noCategoriesProperty().get() ? addCategoryFirst : nameField;
        if (target.getScene() != null && target.isVisible()) {
            pendingFocus = false;
            target.requestFocus();
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private static ProgressIndicator spinner(String id) {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setId(id);
        indicator.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        indicator.setMinSize(SPINNER_SIZE, SPINNER_SIZE);
        indicator.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        indicator.setAccessibleText(LOADING_TEXT);
        return indicator;
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
                    if (!target.isDisabled() && target.isVisible()) {
                        target.requestFocus();
                        return;
                    }
                }
            }
        });
    }

    /** Enter bấm nút đang có focus (Space đã có sẵn). */
    private static void enterFires(Button... buttons) {
        for (Button button : buttons) {
            button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    button.fire();
                }
            });
        }
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminProductFormRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminProductFormRoute> {

        private final ObjectProvider<AdminProductFormPresenter> presenters;
        private final ImageLoader images;
        private final ImageFileDialog fileDialog;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminProductFormPresenter> presenters, ImageLoader images,
                ImageFileDialog fileDialog, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.images = images;
            this.fileDialog = fileDialog;
            this.nav = nav;
        }

        @Override
        public Class<AdminProductFormRoute> routeType() {
            return AdminProductFormRoute.class;
        }

        @Override
        public Node create(AdminProductFormRoute route) {
            AdminProductFormPresenter presenter = presenters.getObject();
            AdminProductFormScreen screen = new AdminProductFormScreen(presenter, images, fileDialog, nav,
                    route.isNew());
            presenter.load(route);
            return screen;
        }
    }
}
