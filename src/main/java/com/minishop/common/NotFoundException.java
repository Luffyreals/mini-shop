package com.minishop.common;

/**
 * Không tìm thấy tài nguyên được yêu cầu (hoặc không thuộc về người gọi, ví dụ đơn của người khác). Message viết
 * bằng tiếng Anh, chỉ dành cho log; màn hình tự chọn câu tiếng Việt để hiện.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
