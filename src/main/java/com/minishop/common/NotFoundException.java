package com.minishop.common;

/** Không tìm thấy tài nguyên được yêu cầu. Message viết bằng tiếng Anh, chỉ dành cho log. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
