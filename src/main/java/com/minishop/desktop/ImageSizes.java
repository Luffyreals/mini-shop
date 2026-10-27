package com.minishop.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Tính cỡ yêu cầu khi nạp ảnh để <b>phủ kín</b> ô (xem {@link ImageLoader#loadCover}). Java thuần, không import
 * JavaFX. JavaFX chỉ cho yêu cầu một khung "vừa khít" giữ tỉ lệ, nên muốn phủ kín phải biết tỉ lệ ảnh trước:
 * {@link #probe} đọc cỡ gốc từ header (vài trăm byte) của tệp {@code file:} hoặc {@code jar:}, không giải mã ảnh.
 */
final class ImageSizes {

    private static final int[] UNKNOWN = {0, 0};

    private ImageSizes() {
    }

    /**
     * Cỡ gốc {@code {rộng, cao}} của ảnh ở {@code resolvedUrl}; {@code {0, 0}} nếu URL không phải {@code file:} hoặc
     * {@code jar:}, hoặc không đọc được. Không bao giờ ném.
     */
    static int[] probe(String resolvedUrl) {
        String lower = resolvedUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("file:") && !lower.startsWith("jar:")) {
            return UNKNOWN;
        }
        try (InputStream in = URI.create(resolvedUrl).toURL().openStream();
             ImageInputStream stream = new MemoryCacheImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return UNKNOWN;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new int[] {width, height} : UNKNOWN;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Cỡ yêu cầu {@code {rộng, cao}} để ảnh {@code size} phủ kín ô {@code boxWidth} x {@code boxHeight}: giữ tỉ lệ
     * ảnh, cạnh ngắn của phần phủ bằng cỡ ô. {@code {0, 0}} (cỡ gốc) khi ảnh không lớn hơn ô, để khỏi phóng to; cỡ ô
     * khi chưa biết cỡ ảnh.
     */
    static int[] coverRequest(int[] size, int boxWidth, int boxHeight) {
        if (size[0] <= 0 || size[1] <= 0) {
            return new int[] {boxWidth, boxHeight};
        }
        double scale = Math.max((double) boxWidth / size[0], (double) boxHeight / size[1]);
        if (scale >= 1) {
            return new int[] {0, 0};
        }
        return new int[] {
            Math.max(1, (int) Math.ceil(size[0] * scale)),
            Math.max(1, (int) Math.ceil(size[1] * scale))
        };
    }
}
