package com.minishop.admin;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/** Null and blank are valid here so that {@code @NotBlank} alone reports a missing value. */
public final class WholeNumberValidator implements ConstraintValidator<WholeNumber, String> {

    // [0-9], not \d: ASCII digits only.
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,6}");

    private long min;
    private long max;

    @Override
    public void initialize(WholeNumber constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String s = value.strip();
        if (!DIGITS.matcher(s).matches()) {
            return false;
        }
        long number = Long.parseLong(s);
        return number >= min && number <= max;
    }
}
