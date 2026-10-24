package com.minishop.cart;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Parses raw request parameters for the cart endpoints. Parameters arrive as strings and a bad value must
 * never become a 500 (same approach as the catalog's {@code ProductFilter}).
 */
final class CartInput {

    private static final Pattern PRODUCT_ID = Pattern.compile("\\d{1,18}");
    private static final Pattern QUANTITY = Pattern.compile("\\d{1,2}");

    private CartInput() {
    }

    /** @return the id, or empty for null, blank, non-numeric, negative or overlong input */
    static Optional<Long> productId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        return PRODUCT_ID.matcher(s).matches() ? Optional.of(Long.valueOf(s)) : Optional.empty();
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
