package com.minishop.cart;

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
 * 404 for {@link CartController}, rendering the catalog's {@code product/not-found} page.
 * Deliberately duplicates {@code catalog.ProductNotFoundAdvice}: catalog must not know cart.
 * GĐ7 gom các advice 404 lại.
 */
@ControllerAdvice(assignableTypes = CartController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CartNotFoundAdvice {

    private static final Logger log = LoggerFactory.getLogger(CartNotFoundAdvice.class);

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e) {
        log.debug("Cart target not found: {}", e.getMessage());
        return "product/not-found";
    }
}
