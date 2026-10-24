package com.minishop.catalog;

import java.util.List;

/** Một trang sản phẩm quản trị. {@code q} là null khi không tìm kiếm; {@code page} đánh số từ 1. */
public record AdminProductPage(List<AdminProductRow> products, String q, int page, int totalPages,
                               long totalElements) {

    /** True khi không có sản phẩm nào khớp (không chỉ là trang rỗng). Public: template đọc qua SpEL. */
    public boolean isEmpty() {
        return totalElements == 0;
    }
}
