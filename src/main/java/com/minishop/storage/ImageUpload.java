package com.minishop.storage;

import org.springframework.core.io.InputStreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Một ảnh đã được kiểm tra. Chỉ tạo được qua {@link #from}: đuôi, content type và chữ ký đầu file (magic bytes)
 * phải cùng chỉ một {@link ImageFormat}, và kích thước không quá {@link #MAX_BYTES}.
 */
public final class ImageUpload {

    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private final ImageFormat format;
    private final byte[] bytes;

    private ImageUpload(ImageFormat format, byte[] bytes) {
        this.format = format;
        this.bytes = bytes;
    }

    /**
     * @return rỗng khi không chọn file (kích thước 0 và tên trống)
     * @throws InvalidImageException khi file quá lớn hoặc không phải JPG/PNG/WebP hợp lệ
     */
    public static Optional<ImageUpload> from(String originalFilename, String contentType, long size,
                                             InputStreamSource content) {
        boolean noName = originalFilename == null || originalFilename.isBlank();
        if (size == 0 && noName) {
            return Optional.empty();
        }
        if (size > MAX_BYTES) {
            throw new InvalidImageException(InvalidImageException.Reason.TOO_LARGE, "Image too large: " + size);
        }
        ImageFormat byExtension = formatOfExtension(originalFilename);
        ImageFormat byContentType = formatOfContentType(contentType);
        if (byExtension == null || byContentType != byExtension) {
            throw unsupported("extension or content type not allowed");
        }

        byte[] data;
        try (InputStream in = content.getInputStream()) {
            data = in.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (data.length > MAX_BYTES) {
            throw new InvalidImageException(InvalidImageException.Reason.TOO_LARGE, "Image too large");
        }
        if (formatOfSignature(data) != byExtension) {
            throw unsupported("file signature does not match " + byExtension);
        }
        return Optional.of(new ImageUpload(byExtension, data));
    }

    public ImageFormat format() {
        return format;
    }

    public long size() {
        return bytes.length;
    }

    /** Nội dung file, cho {@code LocalStorageService}. */
    byte[] bytes() {
        return bytes;
    }

    private static InvalidImageException unsupported(String message) {
        return new InvalidImageException(InvalidImageException.Reason.UNSUPPORTED_TYPE, "Unsupported image: " + message);
    }

    private static ImageFormat formatOfExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (filename.substring(dot + 1).strip().toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> ImageFormat.JPEG;
            case "png" -> ImageFormat.PNG;
            case "webp" -> ImageFormat.WEBP;
            default -> null;
        };
    }

    private static ImageFormat formatOfContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        for (ImageFormat f : ImageFormat.values()) {
            if (f.contentType().equals(base)) {
                return f;
            }
        }
        return null;
    }

    private static ImageFormat formatOfSignature(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return ImageFormat.JPEG;
        }
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (d.length >= png.length && Arrays.equals(d, 0, png.length, png, 0, png.length)) {
            return ImageFormat.PNG;
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return ImageFormat.WEBP;
        }
        return null;
    }

    @Override
    public String toString() {
        return "ImageUpload[format=" + format + ", size=" + bytes.length + "]";
    }
}
