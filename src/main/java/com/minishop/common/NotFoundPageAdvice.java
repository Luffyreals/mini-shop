package com.minishop.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The single 404 mechanism for controllers annotated with {@link NotFoundPage}. A missing resource and a malformed
 * id (not a number, overflowing a long) both end here, never as 400 or 500. Kept at highest precedence because
 * Spring picks advice by {@code @Order}, not by how specific it is. Holds no business logic and knows no feature:
 * the controller names its own view (ADR-0011).
 */
@ControllerAdvice(annotations = NotFoundPage.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotFoundPageAdvice {

    private static final Logger log = LoggerFactory.getLogger(NotFoundPageAdvice.class);

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(Exception e, HandlerMethod handler, Model model) {
        NotFoundPage page = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), NotFoundPage.class);
        if (page == null) {
            // unreachable while the advice is limited to annotated controllers; fail loudly if that ever changes
            throw new IllegalStateException("No @NotFoundPage on " + handler.getBeanType().getName());
        }
        log.debug("Not found ({}): {}", page.view(), e.getMessage());
        addIfNotEmpty(model, "notFoundTitle", page.title());
        addIfNotEmpty(model, "backUrl", page.backUrl());
        addIfNotEmpty(model, "backLabel", page.backLabel());
        addIfNotEmpty(model, "section", page.section());
        return page.view();
    }

    private static void addIfNotEmpty(Model model, String name, String value) {
        if (!value.isEmpty()) {
            model.addAttribute(name, value);
        }
    }
}
