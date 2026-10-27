package com.minishop.desktop;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Nạp ảnh nền cho màn hình, kèm cache LRU ({@value #MAX_ENTRIES} mục theo {@link ImageKey}).
 * <p>
 * JPG/PNG dùng {@code backgroundLoading = true} với kích thước yêu cầu sát kích thước hiển thị, nên giải mã
 * ảnh lớn không làm giật giao diện và không tốn bộ nhớ cỡ gốc; việc nạp chạy trên luồng nội bộ của JavaFX.
 * Gọi trên luồng giao diện.
 * <p>
 * URL {@code file:} kết thúc {@code .webp} (JavaFX không đọc được WebP) đi đường khác: {@link WebpDecoder}
 * giải mã ngoài luồng giao diện qua {@link BackgroundTasks}, kết quả thành {@link WritableImage} trên luồng
 * giao diện và vào cùng cache. Vì chưa có ngay, {@link #load} trả {@code null} cho tới khi ảnh vào cache; ai cần
 * ảnh đến sau gọi {@link #whenLoaded}.
 * <p>
 * {@link #load} trả {@code null} khi URL rỗng, tài nguyên không có hoặc WebP chưa sẵn sàng; ảnh nạp hỏng có
 * {@link Image#isError()} {@code true} và bị bỏ khỏi cache; WebP hỏng không bao giờ gọi callback. Người gọi hiện
 * ô giữ chỗ trong mọi trường hợp đó; {@link ImageTile} làm việc đó.
 */
@Component
public class ImageLoader {

    static final int MAX_ENTRIES = 200;
    private static final Logger log = LoggerFactory.getLogger(ImageLoader.class);
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]+:.*");

    private final BackgroundTasks tasks;
    private final WebpDecoder webp;
    private final LruCache<ImageKey, Image> cache = new LruCache<>(MAX_ENTRIES);
    /** Khóa WebP đã giải mã hỏng: không giải mã lại mỗi lần hiện ô. */
    private final LruCache<ImageKey, Boolean> failedWebp = new LruCache<>(MAX_ENTRIES);
    /** Cỡ gốc của ảnh đã đọc header ({@code {0, 0}} nếu không đọc được), để chọn cỡ yêu cầu khi phủ kín ô. */
    private final LruCache<String, int[]> sizes = new LruCache<>(MAX_ENTRIES);
    /** Khóa WebP đang giải mã, kèm những ai đang đợi (gộp nhiều ô cùng ảnh thành một lần giải mã). */
    private final Map<ImageKey, List<Consumer<Image>>> pendingWebp = new HashMap<>();

    @Autowired
    public ImageLoader(BackgroundTasks tasks) {
        this(tasks, new WebpDecoder());
    }

    ImageLoader(BackgroundTasks tasks, WebpDecoder webp) {
        this.tasks = tasks;
        this.webp = webp;
    }

    /**
     * @param url   URL JavaFX nạp được ({@code file:}, {@code jar:}, {@code http(s):}, {@code data:}) hoặc đường
     *              dẫn classpath như {@code /images/products/x.jpg} (đúng giá trị {@code products.image_path})
     * @param width  chiều rộng hiển thị, điểm ảnh; {@code <= 0} là cỡ gốc
     * @param height chiều cao hiển thị, điểm ảnh; {@code <= 0} là cỡ gốc
     * @return ảnh (có thể còn đang nạp), hoặc {@code null} nếu không có ảnh để nạp hoặc là WebP chưa giải mã xong
     */
    public synchronized Image load(String url, double width, double height) {
        return load(url, width, height, false);
    }

    /**
     * Như {@link #load}, nhưng ảnh được nạp để <b>phủ kín</b> ô {@code width} x {@code height} (cắt giữa, xem
     * {@link ImageTile}): cạnh ngắn của phần phủ bằng cỡ ô, nên ảnh rộng không bị mờ. Cỡ ảnh đọc từ header (JPG/PNG,
     * tệp {@code file:}/{@code jar:}) một lần cho mỗi URL; không đọc được thì nạp vừa khít như {@link #load}.
     * WebP đi đường {@link #whenLoadedCover}.
     */
    public synchronized Image loadCover(String url, double width, double height) {
        return load(url, width, height, true);
    }

    private Image load(String url, double width, double height, boolean cover) {
        String resolved = resolve(url);
        if (resolved == null) {
            return null;
        }
        ImageKey key = cover ? ImageKey.cover(resolved, width, height) : ImageKey.of(resolved, width, height);
        Image cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (isWebp(resolved)) {
            return null;
        }
        Image image;
        try {
            int[] request = cover ? coverRequest(resolved, key) : new int[] {key.width(), key.height()};
            image = new Image(resolved, request[0], request[1], true, true, true);
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

    /**
     * Đường dành cho ảnh WebP chưa có trong cache (xem {@link #load}): giải mã ngoài luồng giao diện rồi gọi
     * {@code onLoaded} <b>trên luồng giao diện</b> với ảnh đã vào cache. Gọi trên luồng giao diện.
     * <ul>
     *   <li>Ảnh đã có trong cache: gọi {@code onLoaded} ngay.
     *   <li>URL không phải WebP, rỗng, hoặc file hỏng/quá lớn/không có: không gọi gì (ô giữ chỗ vẫn hiện).
     *   <li>Nhiều người đợi cùng một ảnh chia sẻ một lần giải mã.
     * </ul>
     * Callback không bị hủy; người gọi tự kiểm tra rằng kết quả vẫn còn cần (xem {@link ImageTile}).
     */
    public void whenLoaded(String url, double width, double height, Consumer<Image> onLoaded) {
        whenLoaded(url, width, height, false, onLoaded);
    }

    /** Như {@link #whenLoaded}, cho ảnh nạp bằng {@link #loadCover} (cùng cache và cùng cách phủ kín). */
    public void whenLoadedCover(String url, double width, double height, Consumer<Image> onLoaded) {
        whenLoaded(url, width, height, true, onLoaded);
    }

    private void whenLoaded(String url, double width, double height, boolean cover, Consumer<Image> onLoaded) {
        String resolved = resolve(url);
        if (resolved == null || !isWebp(resolved)) {
            return;
        }
        ImageKey key = cover ? ImageKey.cover(resolved, width, height) : ImageKey.of(resolved, width, height);
        Image cached;
        synchronized (this) {
            cached = cache.get(key);
            if (cached == null) {
                if (failedWebp.containsKey(key)) {
                    return;
                }
                List<Consumer<Image>> waiting = pendingWebp.get(key);
                if (waiting != null) {
                    waiting.add(onLoaded);
                    return;
                }
                List<Consumer<Image>> first = new ArrayList<>();
                first.add(onLoaded);
                pendingWebp.put(key, first);
            }
        }
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        tasks.submit(() -> decode(resolved, key), result -> finishWebp(key, result),
                // Phòng hờ: lỗi nào khác lọt ra cũng phải dọn pendingWebp, và không hiện hộp thoại
                failure -> finishWebp(key, Optional.empty()));
    }

    /**
     * Chạy trên luồng nền. Mọi lỗi thành rỗng; không bao giờ ném. Ngoài {@link RuntimeException} còn bắt
     * {@link OutOfMemoryError} và lỗi nạp bộ đọc ImageIO ({@link LinkageError}, {@link ServiceConfigurationError}):
     * để chúng lọt ra thì {@link #finishWebp} không chạy, {@code pendingWebp} kẹt mãi và hiện hộp thoại lỗi chung,
     * trái QĐ8 (chỉ hiện ô giữ chỗ).
     */
    private Optional<WebpDecoder.DecodedImage> decode(String resolved, ImageKey key) {
        try {
            Path file = Path.of(URI.create(resolved));
            return webp.decode(file, key.width(), key.height(), key.cover());
        } catch (RuntimeException | OutOfMemoryError | LinkageError | ServiceConfigurationError e) {
            log.debug("Cannot decode WebP: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Nạp sẵn bộ đọc ImageIO một lần, ngoài luồng giao diện: lần gọi {@code ImageIO} đầu tiên quét classpath tìm
     * plugin (chừng 100-300 ms), nếu để {@link ImageSizes#probe} trên luồng giao diện gặp trước thì màn đầu bị khựng.
     * Gọi một lần sau khi context đã dựng, từ {@link MiniShopApp}; lỗi chỉ ghi DEBUG, không hiện hộp thoại.
     */
    void warmUp() {
        tasks.submit(() -> {
            ImageIO.getImageReadersByFormatName("webp");
            return Boolean.TRUE;
        }, null, failure -> log.debug("ImageIO warm-up failed: {}", failure.getClass().getSimpleName()));
    }


    /** Chạy trên luồng giao diện. */
    private void finishWebp(ImageKey key, Optional<WebpDecoder.DecodedImage> result) {
        Image image = null;
        if (result.isPresent()) {
            WebpDecoder.DecodedImage decoded = result.get();
            WritableImage writable = new WritableImage(decoded.width(), decoded.height());
            writable.getPixelWriter().setPixels(0, 0, decoded.width(), decoded.height(),
                    PixelFormat.getIntArgbInstance(), decoded.argb(), 0, decoded.width());
            image = writable;
        }
        List<Consumer<Image>> waiting;
        synchronized (this) {
            waiting = pendingWebp.remove(key);
            if (image != null) {
                cache.put(key, image);
            } else {
                failedWebp.put(key, Boolean.TRUE);
            }
        }
        if (image == null || waiting == null) {
            return;
        }
        RuntimeException failure = null;
        for (Consumer<Image> consumer : waiting) {
            try {
                consumer.accept(image);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Cỡ yêu cầu để ảnh phủ kín ô của {@code key}: giữ tỉ lệ ảnh, cạnh ngắn của phần phủ bằng cỡ ô. {0, 0} (cỡ gốc)
     * khi ảnh nhỏ hơn ô; cỡ ô khi chưa biết cỡ ảnh. Gọi trong khối đồng bộ của {@link #load}.
     */
    private int[] coverRequest(String resolved, ImageKey key) {
        if (key.width() <= 0 || key.height() <= 0) {
            return new int[] {key.width(), key.height()};
        }
        int[] size = sizes.get(resolved);
        if (size == null) {
            size = ImageSizes.probe(resolved);
            sizes.put(resolved, size);
        }
        return ImageSizes.coverRequest(size, key.width(), key.height());
    }

    private synchronized void evict(ImageKey key, Image image) {
        cache.remove(key, image);
    }

    /** Số ảnh đang nằm trong cache (dùng cho test và chẩn đoán). */
    synchronized int cachedCount() {
        return cache.size();
    }

    /** URL {@code file:} kết thúc {@code .webp} (không phân biệt hoa thường). */
    static boolean isWebp(String resolvedUrl) {
        if (resolvedUrl == null) {
            return false;
        }
        String lower = resolvedUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("file:") && lower.endsWith(".webp");
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
