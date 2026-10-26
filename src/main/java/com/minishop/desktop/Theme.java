package com.minishop.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tài nguyên giao diện chung: phông Plus Jakarta Sans và {@code ui/theme.css}.
 * Phông phải được nạp <b>trước</b> khi gắn stylesheet, nếu không CSS sẽ rơi về phông hệ thống.
 */
public final class Theme {

    /** Đường dẫn classpath của bốn file TTF tĩnh (400, 500, 600, 700). */
    public static final List<String> FONT_RESOURCES = List.of(
            "/ui/fonts/PlusJakartaSans-Regular.ttf",
            "/ui/fonts/PlusJakartaSans-Medium.ttf",
            "/ui/fonts/PlusJakartaSans-SemiBold.ttf",
            "/ui/fonts/PlusJakartaSans-Bold.ttf");

    public static final String STYLESHEET = "/ui/theme.css";

    private static final Logger LOG = LoggerFactory.getLogger(Theme.class);

    private static boolean fontsLoaded;
    private static boolean fontsFailed;

    private Theme() {
    }

    /** Nạp các file phông vào JavaFX. Gọi trên luồng giao diện; gọi lại không có tác dụng. */
    public static synchronized void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        for (String resource : FONT_RESOURCES) {
            try (InputStream in = Theme.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Thiếu phông " + resource);
                }
                if (Font.loadFont(in, 15) == null) {
                    throw new IllegalStateException("Không nạp được phông " + resource);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Không đọc được phông " + resource, e);
            }
        }
        fontsLoaded = true;
    }

    /**
     * Như {@link #loadFonts()} nhưng không ném lỗi: phông hỏng chỉ khiến giao diện rơi về phông hệ thống.
     * Dành cho hộp thoại lỗi, vốn phải hiện được cả khi tài nguyên giao diện hỏng. Ghi log một lần.
     *
     * @return {@code true} nếu cả bốn phông đã nạp
     */
    static synchronized boolean tryLoadFonts() {
        if (fontsLoaded) {
            return true;
        }
        if (fontsFailed) {
            return false;
        }
        try {
            loadFonts();
            return true;
        } catch (RuntimeException | LinkageError e) {
            fontsFailed = true;
            LOG.warn("Fonts unavailable, using the system font: {}", e.toString());
            return false;
        }
    }

    /** Nạp phông rồi gắn {@code theme.css} vào scene. */
    public static void apply(Scene scene) {
        loadFonts();
        addStylesheet(scene);
    }

    /** Gắn {@code theme.css} mà không nạp phông (đi cùng {@link #tryLoadFonts()}). */
    static void addStylesheet(Scene scene) {
        URL css = Theme.class.getResource(STYLESHEET);
        if (css == null) {
            throw new IllegalStateException("Thiếu " + STYLESHEET);
        }
        scene.getStylesheets().add(css.toExternalForm());
    }
}
