package com.minishop.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the 404 page of a controller. {@link NotFoundPageAdvice} reads it when a {@link NotFoundException} or a
 * malformed path variable ({@code MethodArgumentTypeMismatchException}) escapes a handler of that controller, and
 * renders {@link #view()} with status 404. The optional attributes feed the admin 404 view (ADR-0011).
 * <p>
 * A controller without this annotation gets the generic "not found" page instead, through the
 * {@code @ResponseStatus(NOT_FOUND)} on {@link NotFoundException}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotFoundPage {

    /** Thymeleaf view name of the 404 page. */
    String view();

    /** Model attribute {@code notFoundTitle}; omitted from the model when empty. */
    String title() default "";

    /** Model attribute {@code backUrl}; omitted from the model when empty. */
    String backUrl() default "";

    /** Model attribute {@code backLabel}; omitted from the model when empty. */
    String backLabel() default "";

    /** Model attribute {@code section} (the active sidebar entry); omitted from the model when empty. */
    String section() default "";
}
