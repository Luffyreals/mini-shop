package com.minishop.cart;

/**
 * Ai đang sở hữu giỏ hiện tại. Chỉ được xác định từ phiên đăng nhập của app, qua {@code CurrentCartOwner}
 * (không từ tham số do màn hình truyền vào), nên không thể truy cập giỏ của người khác.
 */
sealed interface CartOwner {

    /** Đã đăng nhập: giỏ trong DB. */
    record Customer(Long userId) implements CartOwner {
    }

    /** Khách: giỏ trong bộ nhớ của tiến trình. */
    record Guest(GuestCartStore store) implements CartOwner {
    }
}
