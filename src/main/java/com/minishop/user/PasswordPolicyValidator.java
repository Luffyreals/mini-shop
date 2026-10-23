package com.minishop.user;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Optional;

public final class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        Optional<String> violation = PasswordPolicy.violation(value);
        if (violation.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(escape(violation.get())).addConstraintViolation();
        return false;
    }

    /** Messages are fixed text, but escape template metacharacters defensively. */
    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
    }
}
