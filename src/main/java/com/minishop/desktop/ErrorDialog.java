package com.minishop.desktop;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hộp thoại thông báo theo token: thẻ trắng bo 28 trên nền trong suốt, một nút "Đóng" dạng pill. Dùng cho lỗi
 * không lường trước (AC-009-12) và cho thông báo "Mini Shop đang chạy ở một cửa sổ khác." (AC-009-2), chỉ khác
 * câu chữ.
 * <p>
 * Đóng bằng nút, Enter, Esc hoặc Alt+F4; nút "Đóng" nhận focus sẵn. Id cố định cho test: {@code #errorDialog}
 * (thẻ), {@code #errorMessage}, {@code #errorCloseButton}.
 */
final class ErrorDialog {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorDialog.class);

    static final String CLOSE_LABEL = "Đóng";
    private static final double CARD_WIDTH = 480;

    private ErrorDialog() {
    }

    /**
     * Cầu nối từ {@link ErrorReporter}: gọi từ luồng nào cũng được, hộp thoại luôn dựng trên luồng giao diện.
     * Nếu dựng lỗi thì báo đã đóng để lỗi sau còn hiện được hộp thoại.
     */
    static void showLater(ErrorReporter.DialogRequest request) {
        Runnable task = () -> {
            try {
                show(request.message(), request.onClosed());
            } catch (RuntimeException e) {
                request.onClosed().run();
                LOG.warn("Could not build the error dialog", e);
            }
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /**
     * Hiện hộp thoại (không chặn). {@code onClosed} chạy một lần khi hộp thoại đóng, dù đóng bằng cách nào.
     * Gọi trên luồng giao diện. Trả về {@code Stage} để test tra node.
     */
    static Stage show(String message, Runnable onClosed) {
        Theme.tryLoadFonts();

        Label text = new Label(message);
        text.setId("errorMessage");
        text.getStyleClass().addAll("text-h2", "dialog-message");
        text.setWrapText(true);

        Button close = new Button(CLOSE_LABEL);
        close.setId("errorCloseButton");
        close.getStyleClass().addAll("pill", "button-primary");
        close.setDefaultButton(true);
        close.setCancelButton(true);

        VBox card = new VBox(20, text, close);
        card.setId("errorDialog");
        card.getStyleClass().addAll("card", "dialog-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(CARD_WIDTH);
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.setAccessibleText(message);

        StackPane root = new StackPane(card);
        root.getStyleClass().add("dialog-root");

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        applyStyle(scene);

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        Window owner = pickOwner();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(MiniShopApp.TITLE);
        stage.setResizable(false);
        stage.setScene(scene);

        boolean[] closed = {false};
        stage.setOnHidden(event -> {
            if (!closed[0]) {
                closed[0] = true;
                if (onClosed != null) {
                    onClosed.run();
                }
            }
        });
        close.setOnAction(event -> stage.close());

        stage.show();
        stage.toFront();
        close.requestFocus();
        return stage;
    }

    /**
     * Gắn theme; thiếu phông hay stylesheet thì hộp thoại vẫn hiện bằng kiểu mặc định của JavaFX, vì đây là
     * nơi cuối cùng báo lỗi cho người dùng (kể cả lỗi hỏng tài nguyên giao diện).
     */
    private static void applyStyle(Scene scene) {
        try {
            Theme.addStylesheet(scene);
        } catch (RuntimeException e) {
            LOG.warn("Could not apply the theme to the error dialog: {}", e.toString());
        }
    }

    /** Cửa sổ đang hiện (ưu tiên cái đang có focus) để hộp thoại căn giữa theo nó; không có thì {@code null}. */
    private static Window pickOwner() {
        Window first = null;
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            if (window.isFocused()) {
                return window;
            }
            if (first == null) {
                first = window;
            }
        }
        return first;
    }
}
