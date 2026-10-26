package com.minishop.user;

/**
 * Các mục menu tài khoản do tính năng khác cung cấp, theo thứ tự hiện (kế hoạch tổng mục 4). "Đăng xuất" luôn có
 * và nằm cuối; nó thuộc {@code user} nên không nằm ở đây. Java thuần.
 */
public enum AccountMenuItem {

    /** "Đơn hàng của tôi": mọi người đã đăng nhập (D4). */
    ORDERS("accountMenuOrders", "Đơn hàng của tôi", false),
    /** "Quản trị": chỉ {@link Role#ADMIN} (D5). */
    ADMIN("accountMenuAdmin", "Quản trị", true);

    private final String menuId;
    private final String label;
    private final boolean adminOnly;

    AccountMenuItem(String menuId, String label, boolean adminOnly) {
        this.menuId = menuId;
        this.label = label;
        this.adminOnly = adminOnly;
    }

    /** Chữ hiện trên mục menu. */
    public String label() {
        return label;
    }

    /** Id của node menu, cố định cho test UI. */
    String menuId() {
        return menuId;
    }

    /** Vai trò này có được thấy mục không. */
    boolean visibleTo(Role role) {
        return !adminOnly || role == Role.ADMIN;
    }
}
