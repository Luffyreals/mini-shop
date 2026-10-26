package com.minishop.desktop;

import java.net.URL;
import java.util.regex.Pattern;
import javafx.scene.image.Image;
import org.springframework.stereotype.Component;

/**
 * Nạp ảnh nền cho màn hình, kèm cache LRU ({@value #MAX_ENTRIES} mục theo {@link ImageKey}).
 * <p>
 * Ảnh dùng {@code backgroundLoading = true} với kích thước yêu cầu sát kích thước hiển thị, nên giải mã
 * ảnh lớn không làm giật giao diện và không tốn bộ nhớ cỡ gốc. Việc nạp chạy trên luồng nội bộ của JavaFX
 * chứ không qua {@link BackgroundTasks}, vì không đụng DB. Gọi trên luồng giao diện.
 * <p>
 * {@link #load} trả {@code null} khi URL rỗng hoặc tài nguyên không có; ảnh nạp hỏng (kể cả WebP, JavaFX
 * không đọc được) có {@link Image#isError()} {@code true} và bị bỏ khỏi cache. Người gọi hiện ô giữ chỗ
 * trong cả hai trường hợp; {@link ImageTile} làm việc đó.
 */
@Component
public class ImageLoader {

    static final int MAX_ENTRIES = 200;
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]+:.*");

    private final LruCache<ImageKey, Image> cache = new LruCache<>(MAX_ENTRIES);

    /**
     * @param url   URL JavaFX nạp được ({@code file:}, {@code jar:}, {@code http(s):}, {@code data:}) hoặc đường
     *              dẫn classpath như {@code /images/products/x.jpg} (đúng giá trị {@code products.image_path})
     * @param width  chiều rộng hiển thị, điểm ảnh; {@code <= 0} là cỡ gốc
     * @param height chiều cao hiển thị, điểm ảnh; {@code <= 0} là cỡ gốc
     * @return ảnh (có thể còn đang nạp), hoặc {@code null} nếu không có ảnh để nạp
     */
    public synchronized Image load(String url, double width, double height) {
        String resolved = resolve(url);
        if (resolved == null) {
            return null;
        }
        ImageKey key = ImageKey.of(resolved, width, height);
        Image cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Image image;
        try {
            image = new Image(resolved, key.width(), key.height(), true, true, true);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!image.isError()) {
            cache.put(key, image);
            image.errorProperty().addListener((observable, wasError, isError) -> {
                if (isError) {
                    evict(key, image);
                }
            });
        }
        return image;
    }

    private synchronized void evict(ImageKey key, Image image) {
        cache.remove(key, image);
    }

    /** Số ảnh đang nằm trong cache (dùng cho test và chẩn đoán). */
    synchronized int cachedCount() {
        return cache.size();
    }

    /** Đưa đầu vào về URL tuyệt đối; {@code null} nếu rỗng hoặc tài nguyên classpath không tồn tại. */
    static String resolve(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.strip();
        if (HAS_SCHEME.matcher(trimmed).matches()) {
            return trimmed;
        }
        URL resource = ImageLoader.class.getResource(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        return resource == null ? null : resource.toExternalForm();
    }
}
