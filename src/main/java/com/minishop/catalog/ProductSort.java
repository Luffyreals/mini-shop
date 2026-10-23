package com.minishop.catalog;

import org.springframework.data.domain.Sort;

/** Cách sắp xếp danh sách. Luôn có {@code id} làm tiêu chí phụ để phân trang ổn định. */
enum ProductSort {
    NEWEST("newest"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc");

    private final String key;

    ProductSort(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    /** Khóa lạ hoặc {@code null} cho ra {@link #NEWEST}. */
    static ProductSort fromKey(String key) {
        for (ProductSort s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return NEWEST;
    }

    Sort toSort() {
        return switch (this) {
            case NEWEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"));
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id"));
        };
    }
}
