package com.minishop.desktop;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Khối "lỗi nạp" trong màn (D6, QĐ12): sau một lỗi lạ khi nạp lần đầu, màn hiện khối này thay vì để trống, để
 * người dùng có việc làm tiếp ("Thử lại") mà không phải bấm lại menu. Hộp thoại lỗi và dòng log của AC-009-12
 * không đổi (do {@code UnexpectedErrors} lo); khối này chỉ là phần trong màn.
 *
 * <p>Khối là {@code VBox} có class {@code empty-state load-error}, id {@code {idPrefix}LoadError}, chứa câu "Đã có
 * lỗi xảy ra. Vui lòng thử lại." (cùng câu với hộp thoại) và nút "Thử lại" (id {@code {idPrefix}Retry}). Màn tự
 * gắn {@code visible}/{@code managed} vào thuộc tính {@code loadFailed} của presenter, và nếu muốn đặt focus vào
 * nút thì lấy nó bằng {@link #retryButton(VBox)}. Gọi trên luồng giao diện.
 */
public final class LoadError {

    /** Chữ của nút thử lại. */
    public static final String RETRY_TEXT = "Thử lại";

    private LoadError() {
    }

    /**
     * @param idPrefix tiền tố id, ví dụ {@code orders} cho {@code ordersLoadError} và {@code ordersRetry}
     * @param onRetry  việc làm khi bấm "Thử lại" (thường là {@code presenter::retry})
     */
    public static VBox create(String idPrefix, Runnable onRetry) {
        Objects.requireNonNull(idPrefix, "idPrefix");
        Objects.requireNonNull(onRetry, "onRetry");

        Label message = new Label(ErrorReporter.MESSAGE);
        message.getStyleClass().add("section-title");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);   // nhãn xuống dòng không bị cắt "…" khi hiện lần đầu
        message.setAlignment(Pos.CENTER);

        Button retry = new Button(RETRY_TEXT);
        retry.setId(idPrefix + "Retry");
        retry.getStyleClass().addAll("button-secondary", "pill");
        retry.setOnAction(event -> onRetry.run());
        retry.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            // Enter bấm nút đang có focus (JavaFX mặc định chỉ bấm bằng Space khi không có nút mặc định)
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                retry.fire();
            }
        });

        VBox box = new VBox(message, retry);
        box.setId(idPrefix + "LoadError");
        box.getStyleClass().addAll("empty-state", "load-error");
        return box;
    }

    /** Nút "Thử lại" của một khối do {@link #create} dựng, để màn đặt focus vào. */
    public static Button retryButton(VBox loadError) {
        Objects.requireNonNull(loadError, "loadError");
        for (Node child : loadError.getChildren()) {
            if (child instanceof Button button) {
                return button;
            }
        }
        throw new IllegalArgumentException("Not a LoadError block");
    }
}
