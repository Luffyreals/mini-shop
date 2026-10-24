package com.minishop.cart;

/**
 * Ai đang sở hữu giỏ của request hiện tại. Chỉ được xác định từ principal hoặc session
 * (không từ URL hay tham số), nên không thể truy cập giỏ của người khác.
 */
sealed interface CartOwner {

    /** Đã đăng nhập: giỏ trong DB. */
    record Customer(Long userId) implements CartOwner {
    }

    /** Khách: giỏ trong session. */
    record Guest(GuestCartStore store) implements CartOwner {
    }
}
