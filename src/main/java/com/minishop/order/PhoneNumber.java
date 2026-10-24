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

/**
 * A Vietnamese phone number, after removing spaces, dots and hyphens: 10 ASCII digits starting with 0, or
 * {@code +84}/{@code 84} followed by 9 digits whose first digit is not 0 (stored as {@code 0} plus those 9 digits).
 * Foreign numbers are rejected.
 */
@Documented
@Constraint(validatedBy = PhoneNumberValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
@interface PhoneNumber {

    String message() default "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
