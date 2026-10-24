package com.minishop.cart;

/** Trạng thái một dòng giỏ so với sản phẩm hiện tại; {@code CartService} xét theo đúng thứ tự khai báo ngược. */
enum CartLineStatus {
    AVAILABLE,
    LOW_STOCK,
    OUT_OF_STOCK,
    DISCONTINUED
}
