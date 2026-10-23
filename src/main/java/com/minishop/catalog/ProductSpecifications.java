package com.minishop.catalog;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Các điều kiện lọc sản phẩm. Không gọi {@code root.fetch} ở đây vì Specification còn được dùng cho câu count. */
final class ProductSpecifications {

    private static final char ESCAPE = '\\';

    private ProductSpecifications() {
    }

    static Specification<Product> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /** Tìm theo tên, không phân biệt hoa thường, có phân biệt dấu. {@code \ % _} trong {@code q} được escape. */
    static Specification<Product> nameContains(String q) {
        String pattern = "%" + escape(q.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, ESCAPE);
    }

    static Specification<Product> inCategory(Long categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
