package com.minishop.desktop;

/**
 * Khóa cache ảnh của {@link ImageLoader}: URL đã chuẩn hóa cộng kích thước yêu cầu làm tròn lên thành số
 * nguyên điểm ảnh (cùng ảnh ở hai cỡ khác nhau là hai mục khác nhau). Kích thước 0 nghĩa là "cỡ gốc".
 * Java thuần.
 */
record ImageKey(String url, int width, int height) {

    static ImageKey of(String url, double width, double height) {
        return new ImageKey(url, pixels(width), pixels(height));
    }

    private static int pixels(double size) {
        return Double.isNaN(size) || size <= 0 ? 0 : (int) Math.ceil(size);
    }
}
