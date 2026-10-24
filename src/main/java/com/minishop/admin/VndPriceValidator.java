package com.minishop.admin;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Null and blank are valid here so that {@code @NotBlank} alone reports a missing price. */
public final class VndPriceValidator implements ConstraintValidator<VndPrice, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return PriceInput.parse(value).isPresent();
    }
}
