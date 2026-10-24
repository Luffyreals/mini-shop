package com.minishop.cart;

/**
 * Kết quả của một thao tác thêm/sửa giỏ. Service không trả chữ hiển thị; tầng web đổi kết quả thành thông báo.
 *
 * @param outcome  điều gì đã xảy ra
 * @param quantity số lượng của dòng sau thao tác (là n trong "Chỉ còn n sản phẩm trong kho")
 */
record CartChange(Outcome outcome, int quantity) {

    enum Outcome { OK, CAPPED_AT_STOCK, CAPPED_AT_MAX, OUT_OF_STOCK, NOT_IN_CART }
}
