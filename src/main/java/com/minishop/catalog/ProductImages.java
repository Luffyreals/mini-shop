package com.minishop.catalog;

import com.minishop.storage.StorageService;
import org.springframework.stereotype.Component;

/**
 * Chỗ duy nhất {@code catalog} và {@code cart} đổi {@code image_path} thành URL; ủy quyền cho {@link StorageService}
 * để {@code cart} không phải phụ thuộc {@code storage}.
 */
@Component
public class ProductImages {

    private final StorageService storage;

    public ProductImages(StorageService storage) {
        this.storage = storage;
    }

    /** @return URL có scheme mà JavaFX nạp được, hoặc {@code null} khi không có ảnh hoặc giá trị lạ (xem {@link StorageService#toUrl}) */
    public String toUrl(String imagePath) {
        return storage.toUrl(imagePath);
    }
}
