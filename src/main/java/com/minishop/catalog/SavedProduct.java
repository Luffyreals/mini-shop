package com.minishop.catalog;

/** Kết quả thêm sản phẩm: đủ để controller chuyển hướng tới trang sửa và dựng thông báo. */
public record SavedProduct(long id, String name) {
}
