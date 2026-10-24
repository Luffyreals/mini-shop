package com.minishop.order;

/**
 * Expected business outcomes of {@link OrderService#placeOrder}. Unchecked on purpose: Spring's
 * {@code @Transactional} rolls back only for {@link RuntimeException} and {@link Error} by default. A
 * checked exception would leave the method after the transaction committed, keeping the stock already
 * decremented even though no order was placed.
 *
 * <p>Never catch it inside the transaction (a caught exception lets the transaction commit, or ends in
 * {@code UnexpectedRollbackException}); the controller catches it after the transaction has finished.
 * Messages are English, for logs only. No stack trace is captured: these are not errors.
 */
abstract sealed class PlaceOrderException extends RuntimeException {

    private PlaceOrderException(String message) {
        super(message, null, false, false);
    }

    /** The cart has no lines (empty, or already ordered by a concurrent request). */
    static final class EmptyCart extends PlaceOrderException {
        EmptyCart() {
            super("Cart is empty");
        }
    }

    /** A line is discontinued, so the cart cannot be ordered as it is. */
    static final class NotOrderable extends PlaceOrderException {
        NotOrderable() {
            super("Cart contains a product that cannot be ordered");
        }
    }

    /** A product is out of stock or short, found by the check or by the conditional stock update. */
    static final class InsufficientStock extends PlaceOrderException {
        private final String productName;

        InsufficientStock(String productName) {
            super("Insufficient stock");
            this.productName = productName;
        }

        String productName() {
            return productName;
        }
    }

    /** The total differs from what the customer saw; carries the recomputed checkout page. */
    static final class PriceChanged extends PlaceOrderException {
        private final transient CheckoutView current;

        PriceChanged(CheckoutView current) {
            super("Cart total changed");
            this.current = current;
        }

        CheckoutView current() {
            return current;
        }
    }
}
