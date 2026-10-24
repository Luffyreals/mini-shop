package com.minishop.admin;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** A price in whole VND from 1 to 999.999.999 (see {@link PriceInput}). Null and empty are valid. */
@Documented
@Constraint(validatedBy = VndPriceValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
@interface VndPrice {

    String message() default "Giá phải là số nguyên từ 1 đến 999.999.999 ₫.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
