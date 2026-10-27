package com.minishop.admin;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

/**
 * Hộp chọn file ảnh sản phẩm của hệ điều hành (QĐ6 của D5). Là một bean riêng (không nằm trong màn) để test thay
 * bằng bản trả sẵn một file: hộp chọn file của Windows không lái được bằng TestFX.
 *
 * <p>Tên có hậu tố {@code Dialog} nên lớp này được import {@code javafx.stage} (ArchUnit: chỉ các lớp
 * {@code *Screen/*Drawer/*Dialog/*Presenter} và {@code desktop} được import JavaFX). {@link FileChooser} chỉ được
 * tạo trong {@link #choose()}, không bao giờ trong constructor: bean được dựng trên luồng {@code JavaFX-Launcher}.
 *
 * <p>Lớp này chỉ <b>chọn</b> file. Việc kiểm file (kích thước, đuôi, content type, magic bytes) là của
 * {@code ImageUpload.fromFile}, chạy ở nền qua {@code BackgroundTasks} trong presenter, không bao giờ trên luồng
 * giao diện.
 */
@Component
class ImageFileDialog {

    /** Tiêu đề hộp chọn file (nhãn ô "Ảnh sản phẩm" của spec 006). */
    static final String TITLE = "Ảnh sản phẩm";
    /** Tên bộ lọc duy nhất của hộp chọn file. */
    static final String FILTER_NAME = "JPG, PNG hoặc WebP";

    private static final String[] PATTERNS = {
            "*.jpg", "*.jpeg", "*.png", "*.webp", "*.JPG", "*.JPEG", "*.PNG", "*.WEBP"
    };

    /**
     * Mở hộp chọn file và chờ người dùng chọn. Gọi trên luồng giao diện (nút "Chọn ảnh" của màn gọi trong
     * {@code onAction}); cửa sổ chủ là cửa sổ đang có focus.
     *
     * @return file đã chọn; rỗng khi người dùng hủy hộp chọn file
     */
    Optional<Path> choose() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(TITLE);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(FILTER_NAME, PATTERNS));
        File chosen = chooser.showOpenDialog(pickOwner());
        return Optional.ofNullable(chosen).map(File::toPath);
    }

    /**
     * {@link Stage} đang hiện, ưu tiên cái đang có focus; không có thì {@code null} (hộp chọn file không có chủ).
     * Chỉ chọn {@code Stage}: một {@code ContextMenu} hay popup đang mở không bao giờ làm cửa sổ chủ.
     */
    private static Window pickOwner() {
        Stage first = null;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Stage stage) || !stage.isShowing()) {
                continue;
            }
            if (stage.isFocused()) {
                return stage;
            }
            if (first == null) {
                first = stage;
            }
        }
        return first;
    }
}
