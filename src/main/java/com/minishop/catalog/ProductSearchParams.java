package com.minishop.catalog;

/** Tham số thô từ URL. Toàn bộ là String để việc bind không bao giờ thất bại. */
record ProductSearchParams(String q, String category, String sort, String page) {
}
