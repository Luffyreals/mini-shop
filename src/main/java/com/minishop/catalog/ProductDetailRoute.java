package com.minishop.catalog;

import com.minishop.desktop.Route;

/**
 * Màn chi tiết một sản phẩm (QĐ3 của D3).
 *
 * @param productId id sản phẩm
 * @param back      danh sách để quay về khi bấm "Về danh sách sản phẩm" hoặc khi không tìm thấy sản phẩm (giữ
 *                  bộ lọc và trang); {@code null} thành {@link CatalogRoute#all()}. Từ trang chủ hoặc từ giỏ
 *                  hàng thì là {@code CatalogRoute.all()}
 */
public record ProductDetailRoute(long productId, CatalogRoute back) implements Route {

    public ProductDetailRoute {
        if (back == null) {
            back = CatalogRoute.all();
        }
    }
}
