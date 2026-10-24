package com.minishop.order;

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

/** A Vietnamese phone number: 10 ASCII digits starting with 0, after removing spaces, dots and hyphens. */
@Documented
@Constraint(validatedBy = PhoneNumberValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
@interface PhoneNumber {

    String message() default "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
