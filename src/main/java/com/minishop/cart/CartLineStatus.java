package com.minishop.cart;

/** Trạng thái một dòng giỏ so với sản phẩm hiện tại; {@link #of} xét theo thứ tự ưu tiên ngược với khai báo. */
enum CartLineStatus {
    AVAILABLE,
    LOW_STOCK,
    OUT_OF_STOCK,
    DISCONTINUED;

    /**
     * Quy tắc "đặt được" duy nhất (BR-005-2), dùng cho trang giỏ và cho bước đặt hàng:
     * ngừng bán, rồi hết hàng, rồi vượt tồn kho, còn lại là đặt được.
     */
    static CartLineStatus of(boolean active, int stock, int quantity) {
        if (!active) {
            return DISCONTINUED;
        }
        if (stock == 0) {
            return OUT_OF_STOCK;
        }
        if (quantity > stock) {
            return LOW_STOCK;
        }
        return AVAILABLE;
    }
}
