package com.minishop.catalog;

import com.minishop.desktop.Route;

/**
 * Màn danh sách sản phẩm (QĐ3 của D3). Route là nguồn sự thật của bộ lọc, giống URL {@code /products?...} của
 * bản web: mọi thay đổi bộ lọc là một lần điều hướng tới route mới, nên một màn chỉ phục vụ đúng một route
 * bất biến.
 * <p>
 * Constructor chuẩn hóa để hai route cùng nghĩa thì bằng nhau: {@code query} đi qua cùng quy tắc với bộ lọc của
 * service (bỏ ký tự điều khiển, strip, cắt 100 ký tự; rỗng thành {@code null}); {@code sortKey} lạ hoặc
 * {@code null} thành {@code "newest"}. Số trang phải {@code >= 1}. Số trang vượt cuối vẫn hợp lệ (màn hiện
 * "Không tìm thấy sản phẩm phù hợp.", AC-002-11).
 *
 * @param query      từ khóa tìm theo tên, {@code null} nếu không lọc
 * @param categoryId id danh mục, {@code null} nếu mọi danh mục
 * @param sortKey    {@code newest}, {@code price_asc} hoặc {@code price_desc}
 * @param page       trang, đánh số từ 1
 */
public record CatalogRoute(String query, Long categoryId, String sortKey, int page) implements Route {

    public CatalogRoute {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
        query = ProductFilter.normalizeQuery(query);
        sortKey = ProductSort.fromKey(sortKey).key();
    }

    /** Mọi sản phẩm, mới nhất trước, trang 1 ("Hàng mới"). */
    public static CatalogRoute all() {
        return new CatalogRoute(null, null, ProductSort.NEWEST.key(), 1);
    }

    public static CatalogRoute inCategory(long categoryId) {
        return all().withCategory(categoryId);
    }

    /** Tìm theo tên; từ khóa rỗng nghĩa là không lọc ({@link #all()}). */
    public static CatalogRoute search(String query) {
        return all().withQuery(query);
    }

    /** Đổi từ khóa; về trang 1. */
    public CatalogRoute withQuery(String newQuery) {
        return new CatalogRoute(newQuery, categoryId, sortKey, 1);
    }

    /** Đổi danh mục ({@code null} là mọi danh mục); về trang 1. */
    public CatalogRoute withCategory(Long newCategoryId) {
        return new CatalogRoute(query, newCategoryId, sortKey, 1);
    }

    /** Đổi cách sắp xếp; về trang 1. */
    public CatalogRoute withSort(String newSortKey) {
        return new CatalogRoute(query, categoryId, newSortKey, 1);
    }

    /** Đổi trang, giữ nguyên bộ lọc. */
    public CatalogRoute withPage(int newPage) {
        return new CatalogRoute(query, categoryId, sortKey, newPage);
    }
}
