package com.minishop.storage;

/** Các định dạng ảnh được nhận (không nhận GIF, SVG). */
public enum ImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** Đuôi file dùng khi lưu, không có dấu chấm. */
    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }
}
