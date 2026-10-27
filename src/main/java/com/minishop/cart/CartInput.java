package com.minishop.cart;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Parses raw text typed into the cart screens (quantity). Input arrives as strings and a bad value must never
 * become an unexpected error (same approach as the catalog's {@code ProductFilter}).
 */
final class CartInput {

    private static final Pattern QUANTITY = Pattern.compile("\\d{1,2}");

    private CartInput() {
    }

    /** @return the quantity when it is a whole number in 1..99, otherwise empty */
    static OptionalInt quantity(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        String s = raw.strip();
        if (!QUANTITY.matcher(s).matches()) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(s);
        return value >= 1 && value <= CartService.MAX_LINE_QUANTITY ? OptionalInt.of(value) : OptionalInt.empty();
    }
}
