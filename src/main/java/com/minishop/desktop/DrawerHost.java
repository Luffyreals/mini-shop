package com.minishop.desktop;

import java.util.List;
import java.util.Objects;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

/**
 * Drawer trượt từ mép phải trong cửa sổ chính (QĐ1 của D4), dùng cho giỏ hàng. Lớp này chỉ lo khung: lớp phủ mờ,
 * chỗ đặt, hiệu ứng trượt, đóng bằng Esc hoặc bấm ra ngoài, giữ focus trong drawer và trả focus khi đóng. Nội
 * dung ({@code panel}) do tính năng dựng, kể cả tiêu đề và nút "Đóng"; {@code desktop} không biết giỏ hàng là gì.
 * <p>
 * Cách giữ focus và chuột: khi mở, khối phía sau (thanh trên và nội dung) bị {@code setDisable(true)} và có
 * class {@code drawer-open} (CSS bỏ độ mờ mặc định của trạng thái khóa). Node bị khóa không nhận chuột và không
 * vào vòng Tab, nên Tab và Shift+Tab chỉ quay vòng trong drawer. Lớp phủ nằm trên cả khối phía sau, nên bấm ra
 * ngoài (kể cả thanh trên) là đóng drawer. Lớp drawer nằm dưới lớp toast: toast vẫn hiện trên drawer.
 * <p>
 * Mở khi đang mở thì thay nội dung (không trượt lại) và gọi {@code onClosed} của nội dung cũ. {@code onClosed}
 * chạy đúng một lần cho mỗi lần {@code open}. Mọi phương thức gọi trên luồng giao diện; không tạo node trong
 * constructor.
 * <p>
 * Id cố định cho test: {@code #drawerLayer} (lớp, trong {@code main-window.fxml}), {@code #drawerScrim}.
 */
@Component
public class DrawerHost {

    private static final Duration SLIDE_IN = Duration.millis(200);
    private static final Duration SLIDE_OUT = Duration.millis(150);
    /** Quãng trượt dự phòng (px) khi chưa đo được chỗ đặt. */
    private static final double FALLBACK_DISTANCE = 440;
    private static final String OPEN_CLASS = "drawer-open";

    private final ReadOnlyBooleanWrapper showing = new ReadOnlyBooleanWrapper(this, "showing", false);
    private final EventHandler<KeyEvent> escapeFilter = this::onKeyPressed;

    private StackPane layer;
    private Node background;

    // Trạng thái của lần mở hiện tại (null khi đóng).
    private Region scrim;
    private StackPane slot;
    private Region panel;
    private Runnable onClosed;
    private Node opener;
    private Animation slideIn;

    // Lần đóng đang trượt ra (nodes còn nằm trong lớp tới khi hiệu ứng xong).
    private Animation slideOut;
    private List<Node> leaving = List.of();

    /**
     * Gắn lớp drawer ({@code #drawerLayer}) và khối phía sau cần khóa khi mở. {@code MainWindow} gọi một lần,
     * trên luồng giao diện, trước khi gọi các {@link ShellContributor}.
     */
    void attach(StackPane layer, Node background) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.background = Objects.requireNonNull(background, "background");
        // Bộ lọc phím nằm trên Scene (nhận cả khi chưa node nào có focus); chỉ làm việc khi drawer đang mở.
        layer.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, escapeFilter);
            }
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, escapeFilter);
            }
        });
        if (layer.getScene() != null) {
            layer.getScene().addEventFilter(KeyEvent.KEY_PRESSED, escapeFilter);
        }
    }

    /**
     * Mở drawer với {@code panel}. Panel được gắn class {@code drawer-panel} (rộng 400 px theo CSS) và cao bằng
     * vùng cửa sổ. Focus chuyển vào node đầu tiên nhận focus được của panel; tính năng có thể gọi
     * {@code requestFocus()} lên một node cụ thể ngay sau khi gọi hàm này.
     *
     * @param onClosed chạy đúng một lần khi drawer đóng, hoặc khi {@code open} lần nữa thay nội dung; có thể
     *                 {@code null}
     */
    public void open(Region panel, Runnable onClosed) {
        Objects.requireNonNull(panel, "panel");
        if (layer == null) {
            throw new IllegalStateException("DrawerHost chưa được gắn vào cửa sổ chính");
        }
        if (showing.get()) {
            replace(panel, onClosed);
            return;
        }
        finishLeaving();

        Scene scene = layer.getScene();
        opener = scene == null ? null : scene.getFocusOwner();
        this.onClosed = onClosed;
        this.panel = preparePanel(panel);

        scrim = new Region();
        scrim.setId("drawerScrim");
        scrim.getStyleClass().add("drawer-scrim");
        scrim.setOnMouseClicked(event -> close());

        slot = new StackPane(this.panel);
        slot.getStyleClass().add("drawer-slot");
        slot.setPickOnBounds(false);
        slot.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(slot, Pos.CENTER_RIGHT);

        layer.getChildren().addAll(scrim, slot);
        background.setDisable(true);
        background.getStyleClass().add(OPEN_CLASS);
        showing.set(true);

        playSlideIn();
        focusInto(this.panel);
    }

    /** Đóng drawer (nếu đang mở): trả focus, gọi {@code onClosed} một lần. Không làm gì khi đang đóng. */
    public void close() {
        if (!showing.get()) {
            return;
        }
        Runnable callback = onClosed;
        Node restore = opener;
        onClosed = null;
        opener = null;

        stop(slideIn);
        slideIn = null;
        leaving = List.of(scrim, slot);
        playSlideOut(panel);
        scrim = null;
        slot = null;
        panel = null;

        background.setDisable(false);
        background.getStyleClass().remove(OPEN_CLASS);
        if (restore != null && restore.getScene() != null && !restore.isDisabled() && restore.isVisible()) {
            restore.requestFocus();
        }
        showing.set(false);
        if (callback != null) {
            callback.run();
        }
    }

    public boolean isShowing() {
        return showing.get();
    }

    /** {@code true} khi drawer đang mở; đổi về {@code false} ngay khi {@link #close()} (trước khi trượt ra xong). */
    public ReadOnlyBooleanProperty showingProperty() {
        return showing.getReadOnlyProperty();
    }

    private void replace(Region newPanel, Runnable newOnClosed) {
        Runnable previous = onClosed;
        onClosed = newOnClosed;
        panel = preparePanel(newPanel);
        slot.getChildren().setAll(panel);
        focusInto(panel);
        if (previous != null) {
            previous.run();
        }
    }

    private static Region preparePanel(Region panel) {
        if (!panel.getStyleClass().contains("drawer-panel")) {
            panel.getStyleClass().add("drawer-panel");
        }
        panel.setMaxHeight(Double.MAX_VALUE);
        panel.setTranslateX(0);
        return panel;
    }

    private void playSlideIn() {
        slot.applyCss();
        double width = slot.prefWidth(-1);
        double distance = width > 0 ? width : FALLBACK_DISTANCE;

        panel.setTranslateX(distance);
        scrim.setOpacity(0);
        TranslateTransition slide = new TranslateTransition(SLIDE_IN, panel);
        slide.setToX(0);
        FadeTransition fade = new FadeTransition(SLIDE_IN, scrim);
        fade.setToValue(1);
        ParallelTransition transition = new ParallelTransition(slide, fade);
        Animation self = transition;
        transition.setOnFinished(event -> {
            if (slideIn == self) {
                slideIn = null;
            }
        });
        slideIn = transition;
        transition.play();
    }

    /** Trượt panel vừa đóng ra rồi gỡ các node; trong lúc trượt chúng không nhận chuột. */
    private void playSlideOut(Region closingPanel) {
        List<Node> nodes = leaving;
        nodes.forEach(node -> node.setMouseTransparent(true));
        double width = slot.getWidth() > 0 ? slot.getWidth() : FALLBACK_DISTANCE;

        TranslateTransition slide = new TranslateTransition(SLIDE_OUT, closingPanel);
        slide.setToX(width);
        FadeTransition fade = new FadeTransition(SLIDE_OUT, scrim);
        fade.setToValue(0);
        ParallelTransition transition = new ParallelTransition(slide, fade);
        Animation self = transition;
        transition.setOnFinished(event -> {
            if (slideOut == self) {
                finishLeaving();
            }
        });
        slideOut = transition;
        transition.play();
    }

    /** Gỡ ngay các node đang trượt ra (khi mở lại giữa chừng, hoặc khi hiệu ứng xong). */
    private void finishLeaving() {
        stop(slideOut);
        slideOut = null;
        layer.getChildren().removeAll(leaving);
        leaving = List.of();
    }

    private static void stop(Animation animation) {
        if (animation != null) {
            animation.stop();
        }
    }

    /**
     * Đưa focus vào node đầu tiên nhận focus được của {@code root}. Thử ngay; nếu chưa được (node chưa hiện hẳn)
     * thì thử lại một lần ở nhịp sau, trừ khi focus đã nằm trong panel (tính năng tự đặt focus).
     */
    private void focusInto(Region root) {
        if (!requestFirstFocus(root)) {
            Platform.runLater(() -> {
                if (panel == root && !focusIsInside(root)) {
                    requestFirstFocus(root);
                }
            });
        }
    }

    private static boolean requestFirstFocus(Node root) {
        Node target = firstFocusable(root);
        if (target == null) {
            return false;
        }
        target.requestFocus();
        return true;
    }

    private static Node firstFocusable(Node node) {
        if (node.isDisabled() || !node.isVisible()) {
            return null;
        }
        if (node.isFocusTraversable()) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstFocusable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean focusIsInside(Node root) {
        Scene scene = root.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        for (Node node = owner; node != null; node = node.getParent()) {
            if (node == root) {
                return true;
            }
        }
        return false;
    }

    private void onKeyPressed(KeyEvent event) {
        if (showing.get() && !event.isConsumed() && event.getCode() == KeyCode.ESCAPE) {
            event.consume();
            close();
        }
    }
}
