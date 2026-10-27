package com.minishop.desktop;

import java.util.List;
import java.util.Objects;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phần dùng chung của mọi hộp thoại modal theo token (QĐ13 của D2): một {@code Stage} trong suốt, modal theo
 * ứng dụng, chủ là cửa sổ đang có focus, gắn theme, nền {@code .dialog-root} chừa chỗ cho bóng của thẻ.
 * {@link ErrorDialog} và hộp thoại đăng nhập đều dùng; hộp thoại xác nhận ở D4/D5 cũng sẽ dùng.
 * <p>
 * Lớp này chỉ dựng cửa sổ. Người gọi tự đặt id, phím tắt, việc cần làm khi đóng, rồi gọi {@code show()}.
 * Gọi trên luồng giao diện.
 */
public final class ModalDialogs {

    private static final Logger LOG = LoggerFactory.getLogger(ModalDialogs.class);

    private ModalDialogs() {
    }

    /**
     * Dựng (chưa hiện) một cửa sổ modal chứa {@code card}, thường là một {@code VBox} có style class
     * {@code card} và {@code dialog-card}. Tiêu đề cửa sổ là "Mini Shop"; không đổi kích thước được.
     * <p>
     * Thiếu phông hay stylesheet thì hộp thoại vẫn hiện bằng kiểu mặc định của JavaFX, vì {@link ErrorDialog}
     * là nơi cuối cùng báo lỗi cho người dùng, kể cả lỗi hỏng tài nguyên giao diện.
     */
    public static Stage create(Region card) {
        Objects.requireNonNull(card, "card");
        Theme.tryLoadFonts();

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
        centerWhenShownAndResized(stage);
        return stage;
    }

    /**
     * Căn hộp thoại giữa cửa sổ chủ (QĐ14 của D6). Một {@code Stage} không đặt {@code x}/{@code y} được JavaFX căn
     * theo <b>màn hình</b>, cao khoảng 1/3 từ trên xuống, nên khi cửa sổ chính không phóng to hoặc nằm lệch thì
     * hộp thoại lệch hẳn khỏi nó. Căn lại mỗi lần hiện và mỗi lần đổi cỡ (người gọi {@code sizeToScene()} sau khi
     * đổi nội dung); không có cửa sổ chủ thì {@code centerOnScreen()}. Tọa độ của JavaFX là pixel logic nên phép
     * tính như nhau ở mọi mức DPI. Phần bóng của {@code .dialog-root} đối xứng, nên căn theo {@code Stage} cũng là
     * căn theo thẻ.
     */
    private static void centerWhenShownAndResized(Stage stage) {
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> center(stage));
        stage.widthProperty().addListener((observable, oldValue, newValue) -> center(stage));
        stage.heightProperty().addListener((observable, oldValue, newValue) -> center(stage));
    }

    private static void center(Stage stage) {
        if (!stage.isShowing() || Double.isNaN(stage.getWidth()) || Double.isNaN(stage.getHeight())) {
            return;
        }
        Window owner = stage.getOwner();
        if (owner == null || !owner.isShowing() || isIconified(owner) || !isFinite(owner)) {
            stage.centerOnScreen();
            return;
        }
        double x = owner.getX() + (owner.getWidth() - stage.getWidth()) / 2;
        double y = owner.getY() + (owner.getHeight() - stage.getHeight()) / 2;

        // Kẹp vào vùng nhìn thấy (không tính thanh tác vụ) của màn hình chứa tâm cửa sổ chủ
        double ownerCenterX = owner.getX() + owner.getWidth() / 2;
        double ownerCenterY = owner.getY() + owner.getHeight() / 2;
        List<Screen> screens = Screen.getScreensForRectangle(ownerCenterX, ownerCenterY, 1, 1);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        Rectangle2D bounds = screen.getVisualBounds();
        x = Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - stage.getWidth()));
        y = Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - stage.getHeight()));

        stage.setX(x);
        stage.setY(y);
    }

    private static boolean isIconified(Window window) {
        return window instanceof Stage owner && owner.isIconified();
    }

    private static boolean isFinite(Window window) {
        return Double.isFinite(window.getX()) && Double.isFinite(window.getY())
                && Double.isFinite(window.getWidth()) && Double.isFinite(window.getHeight());
    }

    private static void applyStyle(Scene scene) {
        try {
            Theme.addStylesheet(scene);
        } catch (RuntimeException e) {
            LOG.warn("Could not apply the theme to a dialog: {}", e.toString());
        }
    }

    /** Cửa sổ đang hiện (ưu tiên cái đang có focus) để hộp thoại căn giữa theo nó; không có thì {@code null}. */
    static Window pickOwner() {
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
