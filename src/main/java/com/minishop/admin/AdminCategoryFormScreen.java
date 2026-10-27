package com.minishop.admin;

import com.minishop.desktop.ScreenFactory;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Form "Thêm danh mục" / "Sửa danh mục" (spec 006 AC-006-11 đến -14, F2 của D5): "Tên danh mục" (một dòng), "Mô
 * tả (không bắt buộc)" (nhiều dòng), "Lưu" và "Hủy". Chỉ dựng node và gắn vào {@link AdminCategoryFormPresenter};
 * không gọi service. Lỗi hiện ngay dưới ô (chữ, và là phần trợ giúp của ô cho trình đọc màn hình).
 *
 * <p><b>Bàn phím:</b> Enter trong ô tên gửi form; trong ô mô tả thì xuống dòng. Tab và Shift+Tab đi qua các ô theo
 * thứ tự trên màn (ô mô tả không nuốt Tab). Enter và Space đều bấm nút có focus. Sau khi kiểm form sai hoặc tên bị
 * trùng, focus tới ô lỗi đầu tiên (vùng cuộn tự cuộn tới nó). Trong lúc lưu các ô chỉ bị khóa chỉnh sửa
 * ({@code editable = false}) và nút "Lưu" vẫn nhận focus, nên Enter lần hai không làm focus nhảy sang "Hủy".
 *
 * <p>Id cố định cho test: {@code categoryFormScreen}, {@code categoryFormTitle}, {@code categoryFormLoading},
 * {@code categoryNameField}, {@code categoryNameError}, {@code categoryDescriptionField},
 * {@code categoryDescriptionError}, {@code saveCategory}, {@code cancelCategory}, {@code categoryFormBusy};
 * cộng các id của thanh bên ({@link AdminScreen}).
 */
final class AdminCategoryFormScreen extends AdminScreen {

    static final String NAME_LABEL = "Tên danh mục";
    static final String DESCRIPTION_LABEL = "Mô tả (không bắt buộc)";
    static final String SAVE_TEXT = "Lưu";
    static final String CANCEL_TEXT = "Hủy";
    static final String BUSY_TEXT = LOADING_TEXT;

    private static final double SPINNER_SIZE = 22;
    private static final double CARD_MAX_WIDTH = 560;

    private final AdminCategoryFormPresenter presenter;

    private final TextField nameField = new TextField();
    private final TextArea descriptionField = new TextArea();
    private final Button saveButton = new Button(SAVE_TEXT);
    private final Button cancelButton = new Button(CANCEL_TEXT);
    private boolean pendingFocus = true;

    AdminCategoryFormScreen(AdminCategoryFormPresenter presenter, AdminNavPresenter nav) {
        super(AdminSection.CATEGORIES, nav);
        this.presenter = presenter;
        setId("categoryFormScreen");

        Label title = pageTitle("", "categoryFormTitle");
        title.textProperty().bind(presenter.titleProperty());
        HBox head = pageHead(title);

        HBox loadingBox = loadingBox("categoryFormLoading",
                presenter.loadingProperty().and(presenter.loadedProperty().not()));

        VBox card = buildCard();
        show(card, presenter.loadedProperty());

        setBody(page(head, loadingBox, card));

        presenter.firstErrorFieldProperty().addListener((observable, oldField, field) -> {
            if (field != null) {
                inputFor(field).requestFocus();
            }
        });
        presenter.loadedProperty().addListener((observable, wasLoaded, isLoaded) -> {
            if (isLoaded) {
                Platform.runLater(this::tryFocus);
            }
        });
        // Nội dung của ScrollPane chỉ vào Scene khi skin được dựng, có thể sau lúc form đã sẵn sàng: thử lại khi
        // ô tên vào scene (requestFocus trên node chưa có scene thì không làm gì)
        nameField.sceneProperty().addListener((observable, oldScene, newScene) -> {
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
    }

    private VBox buildCard() {
        nameField.setId("categoryNameField");
        descriptionField.setId("categoryDescriptionField");
        nameField.textProperty().bindBidirectional(presenter.nameProperty());
        descriptionField.textProperty().bindBidirectional(presenter.descriptionProperty());
        nameField.setOnAction(event -> presenter.save());
        descriptionField.setPrefRowCount(4);
        descriptionField.setWrapText(true);
        tabThrough(descriptionField, nameField, saveButton, cancelButton);

        saveButton.setId("saveCategory");
        saveButton.getStyleClass().addAll("button-primary", "pill");
        saveButton.setOnAction(event -> presenter.save());
        enterFires(saveButton);

        cancelButton.setId("cancelCategory");
        cancelButton.getStyleClass().addAll("button-secondary", "pill");
        cancelButton.setOnAction(event -> presenter.cancel());
        enterFires(cancelButton);

        ProgressIndicator busy = new ProgressIndicator();
        busy.setId("categoryFormBusy");
        busy.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setMinSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        busy.setAccessibleText(BUSY_TEXT);
        show(busy, presenter.busyProperty());

        HBox actions = new HBox(12, saveButton, cancelButton, busy);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(16,
                field(NAME_LABEL, nameField, "categoryNameError", presenter.nameErrorProperty()),
                field(DESCRIPTION_LABEL, descriptionField, "categoryDescriptionError",
                        presenter.descriptionErrorProperty()),
                actions);
        card.getStyleClass().add("summary-card");
        card.setMaxWidth(CARD_MAX_WIDTH);
        return card;
    }

    /**
     * Nhãn, ô nhập và dòng lỗi ngay dưới ô. Ô nhận nhãn làm chữ cho trình đọc màn hình và dòng lỗi làm phần trợ
     * giúp, nên trình đọc đọc lỗi cùng ô; ô chỉ bị khóa chỉnh sửa khi đang lưu.
     */
    private VBox field(String labelText, TextInputControl input, String errorId, ReadOnlyStringProperty error) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setLabelFor(input);

        input.setAccessibleText(labelText);
        input.accessibleHelpProperty().bind(error);
        input.editableProperty().bind(presenter.busyProperty().not());

        Label errorLabel = new Label();
        errorLabel.setId(errorId);
        errorLabel.getStyleClass().add("field-error");
        errorLabel.setWrapText(true);
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);
        errorLabel.textProperty().bind(error);
        errorLabel.visibleProperty().bind(error.isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        return new VBox(6, label, input, errorLabel);
    }

    private TextInputControl inputFor(AdminCategoryFormPresenter.Field field) {
        return switch (field) {
            case NAME -> nameField;
            case DESCRIPTION -> descriptionField;
        };
    }

    // ------------------------------------------------------------------ focus và bàn phím

    /** Sau khi form dùng được: focus vào ô tên (một lần). */
    private void tryFocus() {
        if (!pendingFocus || getScene() == null || !presenter.loadedProperty().get()
                || nameField.getScene() == null) {
            return;
        }
        pendingFocus = false;
        nameField.requestFocus();
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

    /** Enter bấm nút có focus (JavaFX mặc định chỉ Space). */
    private static void enterFires(Button button) {
        button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                button.fire();
            }
        });
    }

    // ------------------------------------------------------------------ factory

    /** Dựng màn cho {@link AdminCategoryFormRoute}; mỗi lần {@link #create} lấy một presenter mới. */
    @Component
    static final class Factory implements ScreenFactory<AdminCategoryFormRoute> {

        private final ObjectProvider<AdminCategoryFormPresenter> presenters;
        private final AdminNavPresenter nav;

        Factory(ObjectProvider<AdminCategoryFormPresenter> presenters, AdminNavPresenter nav) {
            this.presenters = presenters;
            this.nav = nav;
        }

        @Override
        public Class<AdminCategoryFormRoute> routeType() {
            return AdminCategoryFormRoute.class;
        }

        @Override
        public Node create(AdminCategoryFormRoute route) {
            AdminCategoryFormPresenter presenter = presenters.getObject();
            AdminCategoryFormScreen screen = new AdminCategoryFormScreen(presenter, nav);
            presenter.load(route);
            return screen;
        }
    }
}
