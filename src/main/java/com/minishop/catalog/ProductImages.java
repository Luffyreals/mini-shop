package com.minishop.catalog;

/**
 * Chỗ duy nhất đổi {@code image_path} thành URL hiển thị; dùng chung cho {@code catalog} và {@code cart}.
 * GĐ6 sẽ chuyển việc này sang {@code StorageService}.
 */
public final class ProductImages {

    private ProductImages() {
    }

    /** @return URL của ảnh, hoặc {@code null} khi sản phẩm chưa có ảnh (null hoặc trống) */
    public static String toUrl(String imagePath) {
        return imagePath == null || imagePath.isBlank() ? null : imagePath;
    }
}
