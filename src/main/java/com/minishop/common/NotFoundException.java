package com.minishop.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Không tìm thấy tài nguyên được yêu cầu. Message viết bằng tiếng Anh, chỉ dành cho log.
 * <p>
 * Hai đường xử lý: controller có {@link NotFoundPage} thì {@link NotFoundPageAdvice} vẽ trang 404 riêng của nó;
 * controller không có thì {@code @ResponseStatus} làm Spring gọi {@code sendError(404)} và người dùng thấy trang
 * "Không tìm thấy trang" chung (ADR-0011), không bao giờ là 500.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
