package com.minishop.order;

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
 * 404 for {@link OrderController}: a missing order and an order of someone else look the same (IDOR).
 * Deliberately duplicates the other not-found advices, GĐ7 gom các advice 404 lại.
 */
@ControllerAdvice(assignableTypes = OrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrderNotFoundAdvice {

    private static final Logger log = LoggerFactory.getLogger(OrderNotFoundAdvice.class);

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e) {
        log.debug("Order not found: {}", e.getMessage());
        return "order/not-found";
    }
}
