package com.minishop.desktop;

import java.util.Objects;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
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
        return stage;
    }

    private static void applyStyle(Scene scene) {
        try {
            Theme.addStylesheet(scene);
        } catch (RuntimeException e) {
            LOG.warn("Could not apply the theme to a dialog: {}", e.toString());
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
