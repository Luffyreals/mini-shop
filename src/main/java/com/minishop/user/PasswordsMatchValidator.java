package com.minishop.user;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, RegistrationForm> {

    @Override
    public boolean isValid(RegistrationForm form, ConstraintValidatorContext context) {
        if (form == null) {
            return true;
        }
        String password = form.password() == null ? "" : form.password();
        String confirm = form.confirmPassword() == null ? "" : form.confirmPassword();
        if (password.equals(confirm)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("confirmPassword")
                .addConstraintViolation();
        return false;
    }
}
