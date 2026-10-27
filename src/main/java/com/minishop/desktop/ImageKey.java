package com.minishop.desktop;

/**
 * Khóa cache ảnh của {@link ImageLoader}: URL đã chuẩn hóa cộng kích thước ô hiển thị làm tròn lên thành số
 * nguyên điểm ảnh (cùng ảnh ở hai cỡ khác nhau là hai mục khác nhau), cộng cách phủ ô. {@code cover} true là ảnh
 * được nạp để phủ kín ô (cắt giữa), false là vừa khít trong ô. Kích thước 0 nghĩa là "cỡ gốc". Java thuần.
 */
record ImageKey(String url, int width, int height, boolean cover) {

    /** Khóa kiểu vừa khít trong ô. */
    ImageKey(String url, int width, int height) {
        this(url, width, height, false);
    }

    static ImageKey of(String url, double width, double height) {
        return new ImageKey(url, pixels(width), pixels(height), false);
    }

    /** Khóa kiểu phủ kín ô. */
    static ImageKey cover(String url, double width, double height) {
        return new ImageKey(url, pixels(width), pixels(height), true);
    }

    private static int pixels(double size) {
        return Double.isNaN(size) || size <= 0 ? 0 : (int) Math.ceil(size);
    }
}
