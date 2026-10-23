package com.minishop.user;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = PasswordsMatchValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
@interface PasswordsMatch {

    String message() default "Mật khẩu nhập lại không khớp.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
