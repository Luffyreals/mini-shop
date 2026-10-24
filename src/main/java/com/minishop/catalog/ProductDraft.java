package com.minishop.catalog;

import java.math.BigDecimal;

/**
 * Thông tin sản phẩm đã được controller làm sạch và parse. Constructor chỉ là chốt chặn (form đã kiểm trước):
 * tên 1..200 ký tự, giá nguyên từ 1 đến 999.999.999, mô tả tối đa 4000 ký tự (trống thành {@code null}).
 * Cố ý không có tồn kho, ảnh hay trạng thái: ba thứ đó đi đường riêng (ADR-0010).
 */
public record ProductDraft(String name, long categoryId, BigDecimal price, String description) {

    static final int NAME_MAX = 200;
    static final int DESCRIPTION_MAX = 4000;
    static final BigDecimal PRICE_MAX = BigDecimal.valueOf(999_999_999L);

    public ProductDraft {
        if (name == null || name.isBlank() || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("Product name must be 1.." + NAME_MAX + " characters");
        }
        if (price == null || price.signum() <= 0 || price.compareTo(PRICE_MAX) > 0 || price.scale() > 0) {
            throw new IllegalArgumentException("Product price must be a whole number in 1..999999999");
        }
        if (description != null && description.isBlank()) {
            description = null;
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("Product description must be at most " + DESCRIPTION_MAX + " characters");
        }
    }
}
