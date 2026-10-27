package com.minishop.desktop;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

/**
 * Thông báo ngắn ở đáy cửa sổ ("Đã thêm vào giỏ"). Mỗi lúc một toast, có hàng đợi ({@link ToastQueue}), tự ẩn
 * sau 3 giây (lỗi 5 giây); bấm vào toast để ẩn sớm. Không chặn chuột ngoài chính toast. Toast có thể kèm một
 * nút hành động ({@link ToastAction}, D4), khi đó giữ ít nhất 5 giây.
 * <p>
 * {@link #show} gọi từ luồng nào cũng được và không import JavaFX ở chữ ký, nên presenter dùng được. Toast
 * gọi trước khi cửa sổ gắn lớp phủ sẽ được giữ lại và hiện ngay khi gắn.
 */
@Component
public class Toasts {

    private static final Duration FADE_IN = Duration.millis(150);
    private static final Duration FADE_OUT = Duration.millis(200);

    private final ToastQueue queue = new ToastQueue();
    private final List<ToastQueue.Entry> pending = new ArrayList<>();
    private StackPane layer;

    /** Gắn lớp phủ ({@code #toastLayer}) sẽ chứa toast. Gọi trên luồng giao diện. */
    void attach(StackPane layer) {
        this.layer = layer;
        List<ToastQueue.Entry> early = List.copyOf(pending);
        pending.clear();
        early.forEach(this::offer);
    }

    /** Hiện một thông báo. {@code text} rỗng thì bỏ qua; {@code kind} {@code null} tính là {@code INFO}. */
    public void show(String text, ToastKind kind) {
        show(text, kind, null);
    }

    /**
     * Như {@link #show(String, ToastKind)} nhưng có thêm một nút hành động ({@code action} {@code null} thì không
     * có nút). Bấm nút chạy hành động rồi ẩn toast; toast có nút được giữ ít nhất 5 giây. Toast trùng chữ và loại
     * với toast đang hiện hoặc vừa xếp hàng bị bỏ, bất kể nút.
     */
    public void show(String text, ToastKind kind, ToastAction action) {
        if (text == null || text.isBlank()) {
            return;
        }
        ToastQueue.Entry entry = new ToastQueue.Entry(text.strip(), kind == null ? ToastKind.INFO : kind, action);
        if (Platform.isFxApplicationThread()) {
            offer(entry);
        } else {
            Platform.runLater(() -> offer(entry));
        }
    }

    private void offer(ToastQueue.Entry entry) {
        if (layer == null) {
            pending.add(entry);
            return;
        }
        ToastQueue.Entry now = queue.offer(entry);
        if (now != null) {
            display(now);
        }
    }

    private void display(ToastQueue.Entry entry) {
        Runnable[] finishRef = {() -> { }};
        Node toast = buildToast(entry, () -> finishRef[0].run());
        toast.setOpacity(0);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        layer.getChildren().add(toast);

        FadeTransition fadeIn = new FadeTransition(FADE_IN, toast);
        fadeIn.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(entry.holdMillis()));
        FadeTransition fadeOut = new FadeTransition(FADE_OUT, toast);
        fadeOut.setToValue(0);
        SequentialTransition lifecycle = new SequentialTransition(fadeIn, hold, fadeOut);

        boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            lifecycle.stop();
            layer.getChildren().remove(toast);
            ToastQueue.Entry next = queue.finished();
            if (next != null) {
                display(next);
            }
        };
        finishRef[0] = finish;
        lifecycle.setOnFinished(event -> finish.run());
        toast.setOnMouseClicked(event -> finish.run());
        lifecycle.play();
    }

    /** @param dismiss ẩn toast ngay; nút hành động gọi nó sau khi chạy hành động */
    private static Node buildToast(ToastQueue.Entry entry, Runnable dismiss) {
        ToastKind kind = entry.kind();

        SVGPath glyph = new SVGPath();
        glyph.setContent(switch (kind) {
            case SUCCESS -> "M3.5 8.5 L6.5 11.5 L12.5 5";
            case ERROR -> "M8 3.5 L8 9 M8 12.5 L8 12.6";
            case INFO -> "M8 7 L8 12.5 M8 3.5 L8 3.6";
        });
        glyph.getStyleClass().add("toast-glyph");
        StackPane icon = new StackPane(glyph);
        icon.getStyleClass().addAll("toast-icon", "toast-icon-" + kind.name().toLowerCase());

        Label text = new Label(entry.text());
        text.getStyleClass().add("toast-text");
        text.setWrapText(true);

        HBox toast = new HBox(10, icon, text);
        ToastAction action = entry.action();
        if (action != null) {
            Button button = new Button(action.label());
            button.setId("toastAction");
            button.getStyleClass().addAll("toast-action", "pill");
            // Toast không nhận focus bàn phím: nút chỉ là lối tắt cho chuột (xem ToastAction).
            button.setFocusTraversable(false);
            button.setAccessibleText(action.label());
            button.setOnAction(event -> {
                try {
                    action.action().run();
                } finally {
                    dismiss.run();
                }
            });
            toast.getChildren().add(button);
        }
        toast.getStyleClass().addAll("toast", "pill", "toast-" + kind.name().toLowerCase());
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        toast.setAccessibleRole(AccessibleRole.TEXT);
        toast.setAccessibleText(kind.spokenLabel() + ": " + entry.text());
        return toast;
    }
}
