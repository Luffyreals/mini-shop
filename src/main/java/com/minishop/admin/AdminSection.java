package com.minishop.admin;

import com.minishop.desktop.Route;

/**
 * Bốn mục của thanh bên khu quản trị, theo thứ tự hiện (spec 006 mục 6, câu hỏi 1 của D5: không có "Tồn kho" và
 * "Về cửa hàng" riêng). Mỗi màn quản trị cho biết mình thuộc mục nào để thanh bên đánh dấu. Java thuần.
 */
enum AdminSection {

    DASHBOARD("dashboard", "Tổng quan") {
        @Override
        Route route() {
            return new AdminDashboardRoute();
        }
    },
    ORDERS("orders", "Đơn hàng") {
        @Override
        Route route() {
            return AdminOrdersRoute.all();
        }
    },
    PRODUCTS("products", "Sản phẩm") {
        @Override
        Route route() {
            return AdminProductsRoute.first();
        }
    },
    CATEGORIES("categories", "Danh mục") {
        @Override
        Route route() {
            return new AdminCategoriesRoute();
        }
    };

    private final String key;
    private final String label;

    AdminSection(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Khóa chữ thường ({@code dashboard}, {@code orders}, {@code products}, {@code categories}). */
    String key() {
        return key;
    }

    /** Chữ hiện trên nút của thanh bên (cũng là tiêu đề màn đầu của mục). */
    String label() {
        return label;
    }

    /** Id của nút thanh bên, cố định cho test: {@code adminNav-{key}}. */
    String navId() {
        return "adminNav-" + key;
    }

    /** Route đầu của mục: nơi thanh bên đưa người dùng tới (danh sách ở trang 1, không lọc). */
    abstract Route route();
}
