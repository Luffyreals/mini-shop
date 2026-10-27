package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Màn "Sản phẩm" (danh sách, AC-006-17, QĐ4 của D5). Route là nguồn sự thật của ô tìm và trang, giống URL
 * {@code /admin/products?q=&page=} của bản web: mỗi lần tìm hoặc đổi trang là điều hướng tới một route mới, nên
 * hai route cùng nghĩa thì bằng nhau.
 *
 * <p>Constructor chuẩn hóa {@code query} giống {@code CatalogRoute}: bỏ ký tự điều khiển, strip, cắt 100 ký tự
 * (theo code point), rỗng thành {@code null}. Trang nhỏ hơn 1 thành 1; trang vượt cuối vẫn hợp lệ (service trả
 * trang 1). Màn truyền trang cho service dạng chuỗi ({@code Integer.toString(page)}).
 *
 * @param query từ khóa tìm theo tên, {@code null} nếu không tìm
 * @param page  trang, đánh số từ 1
 */
record AdminProductsRoute(String query, int page) implements Route {

    static final int MAX_QUERY_LENGTH = 100;

    AdminProductsRoute {
        query = normalize(query);
        if (page < 1) {
            page = 1;
        }
    }

    /** Trang đầu, không tìm kiếm: lối vào của mục "Sản phẩm". */
    static AdminProductsRoute first() {
        return new AdminProductsRoute(null, 1);
    }

    /** Tìm theo {@code rawQuery}; về trang 1. Chuỗi rỗng nghĩa là không tìm. */
    AdminProductsRoute search(String rawQuery) {
        return new AdminProductsRoute(rawQuery, 1);
    }

    /** Đổi trang, giữ ô tìm. */
    AdminProductsRoute withPage(int newPage) {
        return new AdminProductsRoute(query, newPage);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String q = raw.replaceAll("\\p{Cntrl}", "").strip();
        if (q.codePointCount(0, q.length()) > MAX_QUERY_LENGTH) {
            q = q.substring(0, q.offsetByCodePoints(0, MAX_QUERY_LENGTH));
        }
        return q.isEmpty() ? null : q;
    }
}
