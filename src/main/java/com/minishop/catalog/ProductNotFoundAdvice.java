package com.minishop.catalog;

import com.minishop.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 404 riêng cho {@link ProductController}. Id không phải số hoặc tràn Long cũng là 404, không bao giờ 400/500.
 * Giữ ưu tiên cao nhất để advice toàn cục thêm sau này không lấy mất.
 */
@ControllerAdvice(assignableTypes = ProductController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProductNotFoundAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProductNotFoundAdvice.class);

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e) {
        log.debug("Product page not found: {}", e.getMessage());
        return "product/not-found";
    }
}
