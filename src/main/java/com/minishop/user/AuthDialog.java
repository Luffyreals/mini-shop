package com.minishop.user;

import com.minishop.desktop.ModalDialogs;
import com.minishop.user.AuthDialogPresenter.Mode;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

/**
 * Hộp thoại đăng nhập/đăng ký (QĐ8 của D2): một cửa sổ modal theo token, hai chế độ. Chỉ dựng node và gắn vào
 * {@link AuthDialogPresenter}; mọi quyết định (lỗi gì, khi nào khóa nút, đăng nhập xong làm gì) nằm ở presenter.
 * <p>
 * Cửa sổ dựng lười ở lần mở đầu, trên luồng giao diện, rồi dùng lại; constructor chỉ gắn một listener, nên context
 * khởi động được cả khi chưa có toolkit (không bean nào dựng node trong constructor).
 * <p>
 * Enter trong ô nhập hoặc trên nút thì gửi; Esc và nút "Đóng" gọi {@code presenter.close()}, bị từ chối khi đang
 * đăng nhập hoặc đăng ký (Alt+F4 cũng vậy). Cửa sổ tự co giãn theo nội dung khi lỗi hiện ra hoặc đổi chế độ.
 * <p>
 * Id cố định cho test: {@code #authDialog}, {@code #authTitle}, {@code #authNotice}, {@code #authClose};
 * đăng nhập {@code #loginEmail}, {@code #loginPassword}, {@code #loginError}, {@code #loginSubmit},
 * {@code #toRegister}; đăng ký {@code #regFullName}, {@code #regEmail}, {@code #regPassword},
 * {@code #regConfirmPassword}, {@code #regFullNameError}, {@code #regEmailError}, {@code #regPasswordError},
 * {@code #regConfirmPasswordError}, {@code #regSubmit}, {@code #toLogin}.
 */
@Component
class AuthDialog {

    static final String LOGIN_TITLE = "Đăng nhập";
    static final String REGISTER_TITLE = "Đăng ký";
    static final String CLOSE_LABEL = "Đóng";
    static final String PASSWORD_HINT = "Ít nhất 8 ký tự, gồm chữ và số.";
    static final String BUSY_TEXT = "Đang xử lý…";

    private static final double CARD_WIDTH = 440;
    private static final double SPINNER_SIZE = 24;
    private static final double FIT_TOLERANCE = 0.5;
    /** Đệm ngang (12) và viền (2) của nút chữ: dịch ngược để chữ thẳng hàng với mép ô nhập, viền focus vẫn đủ chỗ. */
    private static final double LINK_INSET = 14;

    private final AuthDialogPresenter presenter;

    private Stage stage;
    private Region card;
    private boolean fitScheduled;
    private TextField loginEmail;
    private PasswordField loginPassword;
    private TextField regFullName;
    private TextField regEmail;
    private PasswordField regPassword;
    private PasswordField regConfirmPassword;
    private Label regFullNameError;
    private Label regEmailError;
    private Label regPasswordError;
    private Label regConfirmPasswordError;

    AuthDialog(AuthDialogPresenter presenter) {
        this.presenter = presenter;
        presenter.showingProperty().addListener((observable, was, now) -> {
            if (now) {
                show();
            } else if (stage != null) {
                stage.hide();
            }
        });
    }

    // ------------------------------------------------------------------ hiện và ẩn

    private void show() {
        if (stage == null) {
            stage = build();
        }
        stage.sizeToScene();
        stage.show();
        stage.toFront();
        fit();
        centerOnOwner();
        focusSuggested();
    }

    private Stage build() {
        card = buildCard();
        Stage dialog = ModalDialogs.create(card);
        dialog.setOnCloseRequest(event -> {
            event.consume();
            presenter.close();
        });
        // Hộp thoại bị ẩn từ ngoài (ví dụ cửa sổ chủ đóng): đồng bộ lại trạng thái của presenter.
        dialog.setOnHidden(event -> presenter.close());

        Scene scene = dialog.getScene();
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                presenter.close();
            }
        });

        presenter.modeProperty().addListener((observable, was, now) -> {
            if (presenter.showingProperty().get()) {
                Platform.runLater(this::focusSuggested);
            }
        });
        presenter.busyProperty().addListener((observable, was, now) -> {
            if (was && !now && presenter.showingProperty().get()) {
                Platform.runLater(this::focusSuggested);
            }
        });
        // Nội dung đổi kích thước (lỗi hiện ra, đổi chế độ) thì cửa sổ phải theo.
        card.needsLayoutProperty().addListener((observable, was, now) -> {
            if (now && !fitScheduled) {
                fitScheduled = true;
                Platform.runLater(() -> {
                    fitScheduled = false;
                    fit();
                });
            }
        });
        return dialog;
    }

    /** Co giãn cửa sổ vừa nội dung, giữ nguyên tâm theo chiều dọc. Chạy trên luồng giao diện. */
    private void fit() {
        if (stage == null || !stage.isShowing()) {
            return;
        }
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        // Nhãn tự xuống dòng nên chiều cao phụ thuộc chiều rộng: hỏi ở đúng chiều rộng của cửa sổ.
        double needed = scene.getRoot().prefHeight(scene.getWidth());
        if (Math.abs(needed - scene.getHeight()) <= FIT_TOLERANCE) {
            return;
        }
        double centerY = stage.getY() + stage.getHeight() / 2;
        stage.sizeToScene();
        stage.setY(centerY - stage.getHeight() / 2);
    }

    private void centerOnOwner() {
        Window owner = stage.getOwner();
        if (owner == null || !owner.isShowing()) {
            return;
        }
        stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
    }

    /**
     * Ô nên có focus: ở đăng nhập là email, hoặc mật khẩu khi email đã có (sau đăng ký hay sau lần sai); ở đăng
     * ký là ô đầu tiên đang báo lỗi, không có thì ô họ tên.
     */
    private void focusSuggested() {
        if (stage == null || !stage.isShowing()) {
            return;
        }
        if (presenter.modeProperty().get() == Mode.LOGIN) {
            (loginEmail.getText().isBlank() ? loginEmail : loginPassword).requestFocus();
            return;
        }
        TextInputControl target = regFullName;
        if (!regFullNameError.getText().isEmpty()) {
            target = regFullName;
        } else if (!regEmailError.getText().isEmpty()) {
            target = regEmail;
        } else if (!regPasswordError.getText().isEmpty()) {
            target = regPassword;
        } else if (!regConfirmPasswordError.getText().isEmpty()) {
            target = regConfirmPassword;
        }
        target.requestFocus();
    }

    // ------------------------------------------------------------------ dựng node

    private Region buildCard() {
        Label title = new Label();
        title.setId("authTitle");
        title.getStyleClass().add("dialog-title");
        title.textProperty().bind(Bindings.when(presenter.modeProperty().isEqualTo(Mode.LOGIN))
                .then(LOGIN_TITLE).otherwise(REGISTER_TITLE));

        SVGPath tick = new SVGPath();
        tick.setContent("M4 11 L9 16 L20 5");
        tick.getStyleClass().add("form-notice-icon");
        Label notice = new Label();
        notice.setId("authNotice");
        notice.getStyleClass().add("form-notice");
        notice.setGraphic(tick);
        notice.textProperty().bind(presenter.noticeProperty());
        notice.accessibleTextProperty().bind(presenter.noticeProperty());
        showWhenNotEmpty(notice, presenter.noticeProperty());

        VBox content = new VBox(16, title, notice, buildLoginForm(), buildRegisterForm());

        Button close = new Button(CLOSE_LABEL);
        close.setId("authClose");
        close.getStyleClass().add("link-button");
        close.setAccessibleText(CLOSE_LABEL);
        close.setTranslateX(LINK_INSET);
        close.disableProperty().bind(presenter.busyProperty());
        close.setOnAction(event -> presenter.close());
        enterFires(close);

        StackPane root = new StackPane(content, close);
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        root.setId("authDialog");
        root.getStyleClass().addAll("card", "dialog-card");
        root.setMinWidth(CARD_WIDTH);
        root.setPrefWidth(CARD_WIDTH);
        root.setMaxWidth(CARD_WIDTH);
        root.setMaxHeight(Region.USE_PREF_SIZE);
        root.accessibleTextProperty().bind(title.textProperty());
        return root;
    }

    private VBox buildLoginForm() {
        loginEmail = new TextField();
        loginPassword = new PasswordField();
        Label error = errorLabel("loginError", presenter.loginErrorProperty());
        loginEmail.textProperty().bindBidirectional(presenter.loginEmailProperty());
        loginPassword.textProperty().bindBidirectional(presenter.loginPasswordProperty());
        loginEmail.setOnAction(event -> presenter.submitLogin());
        loginPassword.setOnAction(event -> presenter.submitLogin());

        VBox form = new VBox(14,
                field("Email", loginEmail, "loginEmail", presenter.loginErrorProperty(), null, null),
                field("Mật khẩu", loginPassword, "loginPassword", presenter.loginErrorProperty(), error, null),
                actions(primaryButton("loginSubmit", LOGIN_TITLE, presenter::submitLogin)),
                linkButton("toRegister", "Chưa có tài khoản? Đăng ký", presenter::showRegister));
        form.setAlignment(Pos.TOP_LEFT);
        showWhenMode(form, Mode.LOGIN);
        return form;
    }

    private VBox buildRegisterForm() {
        regFullName = new TextField();
        regEmail = new TextField();
        regPassword = new PasswordField();
        regConfirmPassword = new PasswordField();
        regFullNameError = errorLabel("regFullNameError", presenter.regFullNameErrorProperty());
        regEmailError = errorLabel("regEmailError", presenter.regEmailErrorProperty());
        regPasswordError = errorLabel("regPasswordError", presenter.regPasswordErrorProperty());
        regConfirmPasswordError = errorLabel("regConfirmPasswordError", presenter.regConfirmPasswordErrorProperty());

        regFullName.textProperty().bindBidirectional(presenter.regFullNameProperty());
        regEmail.textProperty().bindBidirectional(presenter.regEmailProperty());
        regPassword.textProperty().bindBidirectional(presenter.regPasswordProperty());
        regConfirmPassword.textProperty().bindBidirectional(presenter.regConfirmPasswordProperty());
        for (TextField input : new TextField[] {regFullName, regEmail, regPassword, regConfirmPassword}) {
            input.setOnAction(event -> presenter.submitRegister());
        }

        VBox form = new VBox(14,
                field("Họ và tên", regFullName, "regFullName", presenter.regFullNameErrorProperty(),
                        regFullNameError, null),
                field("Email", regEmail, "regEmail", presenter.regEmailErrorProperty(), regEmailError, null),
                field("Mật khẩu", regPassword, "regPassword", presenter.regPasswordErrorProperty(),
                        regPasswordError, PASSWORD_HINT),
                field("Nhập lại mật khẩu", regConfirmPassword, "regConfirmPassword",
                        presenter.regConfirmPasswordErrorProperty(), regConfirmPasswordError, null),
                actions(primaryButton("regSubmit", REGISTER_TITLE, presenter::submitRegister)),
                linkButton("toLogin", "Đã có tài khoản? Đăng nhập", presenter::showLogin));
        form.setAlignment(Pos.TOP_LEFT);
        showWhenMode(form, Mode.REGISTER);
        return form;
    }

    /**
     * Nhãn, ô nhập, gợi ý (nếu có) và dòng lỗi (nếu có) ngay dưới ô. Ô nhận nhãn làm accessible text và dòng lỗi
     * làm accessible help, nên trình đọc màn hình đọc lỗi cùng ô; ô bị khóa khi đang chạy.
     */
    private VBox field(String labelText, TextInputControl input, String id, ReadOnlyStringProperty error,
                       Label errorLabel, String hint) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setLabelFor(input);

        input.setId(id);
        input.setAccessibleText(labelText);
        input.accessibleHelpProperty().bind(error);
        input.disableProperty().bind(presenter.busyProperty());

        VBox box = new VBox(6, label, input);
        if (hint != null) {
            Label hintLabel = new Label(hint);
            hintLabel.getStyleClass().add("field-hint");
            box.getChildren().add(hintLabel);
        }
        if (errorLabel != null) {
            box.getChildren().add(errorLabel);
        }
        return box;
    }

    private static Label errorLabel(String id, ReadOnlyStringProperty text) {
        Label label = new Label();
        label.setId(id);
        label.getStyleClass().add("field-error");
        label.textProperty().bind(text);
        showWhenNotEmpty(label, text);
        return label;
    }

    private Button primaryButton(String id, String text, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().addAll("pill", "button-primary");
        button.disableProperty().bind(presenter.busyProperty());
        button.setOnAction(event -> action.run());
        enterFires(button);
        return button;
    }

    private Button linkButton(String id, String text, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("link-button");
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setTranslateX(-LINK_INSET);
        button.disableProperty().bind(presenter.busyProperty());
        button.setOnAction(event -> action.run());
        enterFires(button);
        return button;
    }

    /** Nút chính kèm vòng tròn chờ, chỉ hiện khi đang chạy. */
    private HBox actions(Button submit) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(SPINNER_SIZE, SPINNER_SIZE);
        progress.setMinSize(SPINNER_SIZE, SPINNER_SIZE);
        progress.setMaxSize(SPINNER_SIZE, SPINNER_SIZE);
        progress.setAccessibleText(BUSY_TEXT);
        progress.visibleProperty().bind(presenter.busyProperty());
        progress.managedProperty().bind(progress.visibleProperty());

        HBox row = new HBox(14, submit, progress);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Nút bấm được bằng Enter như bằng Space (JavaFX mặc định chỉ Space); chặn sự kiện để không bắn hai lần. */
    private static void enterFires(Button button) {
        button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                button.fire();
            }
        });
    }

    private void showWhenMode(Region form, Mode mode) {
        form.visibleProperty().bind(presenter.modeProperty().isEqualTo(mode));
        form.managedProperty().bind(form.visibleProperty());
    }

    private static void showWhenNotEmpty(Label label, ReadOnlyStringProperty text) {
        label.visibleProperty().bind(text.isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
    }
}
