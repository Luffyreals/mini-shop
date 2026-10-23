package com.minishop.catalog;

/** Bộ lọc đã chuẩn hóa: {@code q} là null nếu không lọc, {@code page >= 1}. */
record ProductFilter(String q, Long categoryId, ProductSort sort, int page) {

    static final int MAX_QUERY_LENGTH = 100;

    /**
     * Trang lớn nhất được chấp nhận. Lớn hơn thì kẹp về đây (vẫn là trang rỗng), vì offset = (page - 1) * 12
     * vượt {@code Integer.MAX_VALUE} sẽ làm Spring Data ném lỗi, tức là 500 (BR-002-3).
     */
    static final int MAX_PAGE = 10_000;

    static ProductFilter from(ProductSearchParams params) {
        return new ProductFilter(
                normalizeQuery(params.q()),
                parseLong(params.category()),
                ProductSort.fromKey(params.sort()),
                parsePage(params.page()));
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) {
            return null;
        }
        // Bỏ ký tự điều khiển: PostgreSQL từ chối byte 0x00 trong tham số truy vấn (sẽ thành 500)
        String q = raw.replaceAll("\\p{Cntrl}", "").strip();
        if (q.codePointCount(0, q.length()) > MAX_QUERY_LENGTH) {
            // Cắt theo code point để không chẻ đôi emoji (cặp surrogate)
            q = q.substring(0, q.offsetByCodePoints(0, MAX_QUERY_LENGTH));
        }
        return q.isEmpty() ? null : q;
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parsePage(String raw) {
        if (raw == null) {
            return 1;
        }
        try {
            int page = Integer.parseInt(raw.strip());
            return Math.clamp(page, 1, MAX_PAGE);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
