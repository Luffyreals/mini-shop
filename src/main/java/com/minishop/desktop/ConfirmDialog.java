package com.minishop.desktop;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

/**
 * Hộp xác nhận cho việc không hoàn tác được (hủy đơn ở D4, xóa ở D5), dựng bằng {@link ModalDialogs}: một câu hỏi,
 * nút từ chối và nút xác nhận (màu nguy hiểm). Presenter gọi {@link #ask} như gọi {@link Toasts}, nên test
 * presenter kiểm được "chưa xác nhận thì chưa gọi service".
 * <p>
 * Nút từ chối nhận focus sẵn và Enter không bao giờ xác nhận khi focus còn ở đó: không có nút mặc định, Enter chỉ
 * bấm nút đang có focus. Esc, Alt+F4 hoặc nút từ chối: đóng, không gọi gì. Nút xác nhận: đóng rồi gọi
 * {@code onConfirm} đúng một lần.
 * <p>
 * Gọi trên luồng giao diện. Không tạo {@code Stage} trong constructor (bean được dựng ngoài luồng giao diện).
 * Id cố định cho test: {@code #confirmDialog} (thẻ), {@code #confirmMessage}, {@code #confirmAccept},
 * {@code #confirmDismiss}.
 */
@Component
public class ConfirmDialog {

    private static final double CARD_WIDTH = 480;

    /**
     * Hiện hộp xác nhận (không chặn).
     *
     * @param message      câu hỏi, ví dụ "Bạn có chắc muốn hủy đơn hàng này?"
     * @param confirmLabel chữ nút xác nhận, ví dụ "Hủy đơn hàng"
     * @param dismissLabel chữ nút từ chối, ví dụ "Giữ đơn hàng"
     * @param onConfirm    việc làm khi xác nhận, chạy trên luồng giao diện sau khi hộp thoại đã đóng
     */
    public void ask(String message, String confirmLabel, String dismissLabel, Runnable onConfirm) {
        show(message, confirmLabel, dismissLabel, onConfirm);
    }

    /** Như {@link #ask} nhưng trả {@code Stage} để test tra node. */
    Stage show(String message, String confirmLabel, String dismissLabel, Runnable onConfirm) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(confirmLabel, "confirmLabel");
        Objects.requireNonNull(dismissLabel, "dismissLabel");
        Objects.requireNonNull(onConfirm, "onConfirm");

        Label text = new Label(message);
        text.setId("confirmMessage");
        text.getStyleClass().addAll("text-h2", "dialog-message");
        text.setWrapText(true);

        Button dismiss = new Button(dismissLabel);
        dismiss.setId("confirmDismiss");
        dismiss.getStyleClass().addAll("pill", "button-secondary");
        // Esc bấm nút từ chối. Không đặt nút mặc định nào: Enter không được xác nhận thay người dùng.
        dismiss.setCancelButton(true);

        Button accept = new Button(confirmLabel);
        accept.setId("confirmAccept");
        accept.getStyleClass().addAll("pill", "button-danger");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(12, spacer, dismiss, accept);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(24, text, buttons);
        card.setId("confirmDialog");
        card.getStyleClass().addAll("card", "dialog-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(CARD_WIDTH);
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.setAccessibleText(message);

        Stage stage = ModalDialogs.create(card);

        boolean[] answered = {false};
        dismiss.setOnAction(event -> {
            answered[0] = true;
            stage.close();
        });
        accept.setOnAction(event -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            stage.close();
            onConfirm.run();
        });
        // Enter bấm nút đang có focus (JavaFX mặc định chỉ bấm bằng Space khi không có nút mặc định).
        // Hai nút đều idempotent nhờ cờ answered, nên không lo bị gọi hai lần.
        dismiss.addEventHandler(KeyEvent.KEY_PRESSED, event -> fireOnEnter(event, dismiss));
        accept.addEventHandler(KeyEvent.KEY_PRESSED, event -> fireOnEnter(event, accept));

        stage.show();
        stage.toFront();
        dismiss.requestFocus();
        return stage;
    }

    private static void fireOnEnter(KeyEvent event, Button button) {
        if (event.getCode() == KeyCode.ENTER) {
            event.consume();
            button.fire();
        }
    }
}
