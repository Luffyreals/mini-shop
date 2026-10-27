package com.minishop.desktop;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Giải mã ảnh WebP bằng {@code javax.imageio} (bộ đọc {@code webp} do thư viện chạy lúc runtime cung cấp,
 * JavaFX tự nó không đọc được WebP). Java thuần, không import JavaFX; chạy ngoài luồng giao diện.
 * <p>
 * File là dữ liệu không tin cậy: một ảnh upload qua được kiểm tra magic bytes dù phần còn lại là rác. Vì vậy
 * kích thước được đọc từ header <b>trước</b> khi giải mã, ảnh trên {@value #MAX_PIXELS} điểm ảnh bị từ chối,
 * và mọi {@link IOException} hay {@link RuntimeException} (kể cả không có bộ đọc) thành {@link Optional#empty()}
 * kèm một dòng DEBUG, không có stack trace.
 * <p>
 * Ảnh trả về được thu nhỏ để vừa trong {@value #BOX_FACTOR} lần ô yêu cầu (giữ tỉ lệ): trước hết bằng
 * {@link ImageReadParam#setSourceSubsampling}, rồi bằng thu nhỏ sau giải mã nếu bộ đọc bỏ qua subsampling hoặc
 * còn lớn hơn. Ảnh nhỏ hơn thì giữ nguyên, không phóng to.
 */
final class WebpDecoder {

    /** Ảnh khai nhiều điểm ảnh hơn mức này bị từ chối ngay từ header. */
    static final long MAX_PIXELS = 24_000_000L;

    /** Ảnh giải mã tối đa gấp chừng này lần ô hiển thị (để còn nét trên màn hình có tỉ lệ cao). */
    static final int BOX_FACTOR = 2;

    private static final Logger log = LoggerFactory.getLogger(WebpDecoder.class);

    /** Ảnh đã giải mã: điểm ảnh ARGB không nhân trước alpha, {@code argb.length == width * height}. */
    record DecodedImage(int width, int height, int[] argb) {}

    /**
     * @param maxWidth  chiều rộng ô hiển thị, điểm ảnh; {@code <= 0} là không giới hạn
     * @param maxHeight chiều cao ô hiển thị, điểm ảnh; {@code <= 0} là không giới hạn
     * @return ảnh đã giải mã, hoặc rỗng nếu file không đọc được, hỏng hoặc quá lớn
     */
    Optional<DecodedImage> decode(Path file, int maxWidth, int maxHeight) {
        return decode(file, maxWidth, maxHeight, false);
    }

    /**
     * Như {@link #decode(Path, int, int)}; {@code cover} true thì ảnh được thu nhỏ vừa đủ để phủ kín ô
     * (cạnh ngắn của phần phủ đạt {@value #BOX_FACTOR} lần ô) thay vì vừa khít trong ô.
     */
    Optional<DecodedImage> decode(Path file, int maxWidth, int maxHeight, boolean cover) {
        try {
            return Optional.of(read(file, maxWidth, maxHeight, cover));
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot decode WebP {}: {}", file == null ? null : file.getFileName(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static DecodedImage read(Path file, int maxWidth, int maxHeight, boolean cover) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                throw new IOException("no stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
            if (!readers.hasNext()) {
                throw new IOException("no webp reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IOException("bad size " + width + "x" + height);
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(width, height, maxWidth, maxHeight);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("no image");
                }
                return toArgb(image, maxWidth, maxHeight, cover);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Bước lấy mẫu lớn nhất mà ảnh vẫn không nhỏ hơn {@value #BOX_FACTOR} lần ô; 1 nếu không cần. */
    static int subsampling(int width, int height, int maxWidth, int maxHeight) {
        double byWidth = maxWidth > 0 ? (double) width / (BOX_FACTOR * maxWidth) : Double.MAX_VALUE;
        double byHeight = maxHeight > 0 ? (double) height / (BOX_FACTOR * maxHeight) : Double.MAX_VALUE;
        double ratio = Math.min(byWidth, byHeight);
        return ratio == Double.MAX_VALUE ? 1 : Math.max(1, (int) Math.floor(ratio));
    }

    private static DecodedImage toArgb(BufferedImage source, int maxWidth, int maxHeight, boolean cover) {
        int width = source.getWidth();
        int height = source.getHeight();
        double byWidth = maxWidth > 0 ? (double) BOX_FACTOR * maxWidth / width : -1;
        double byHeight = maxHeight > 0 ? (double) BOX_FACTOR * maxHeight / height : -1;
        // vừa khít: theo cạnh chật hơn (tỉ lệ nhỏ hơn); phủ kín: theo cạnh cần nhiều hơn (tỉ lệ lớn hơn)
        double scale = 1;
        if (cover) {
            scale = Math.min(1, Math.max(byWidth, byHeight) > 0 ? Math.max(byWidth, byHeight) : 1);
        } else {
            if (byWidth > 0) {
                scale = Math.min(scale, byWidth);
            }
            if (byHeight > 0) {
                scale = Math.min(scale, byHeight);
            }
        }
        if (scale >= 1) {
            return new DecodedImage(width, height, source.getRGB(0, 0, width, height, null, 0, width));
        }
        int targetWidth = Math.max(1, (int) Math.floor(width * scale));
        int targetHeight = Math.max(1, (int) Math.floor(height * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return new DecodedImage(targetWidth, targetHeight,
                scaled.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth));
    }
}
