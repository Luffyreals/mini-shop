package com.minishop.cart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Header cart count for one template render, exposed to Thymeleaf as {@code #cartHeader}.
 * The value is computed on the first call and memoized, so a page costs at most one query.
 * The count includes every line (also out-of-stock ones). Not thread-safe; one instance per render.
 */
final class CartHeader {

    private static final Logger log = LoggerFactory.getLogger(CartHeader.class);

    private final CartService cartService;
    private final Authentication authentication;
    private final HttpServletRequest request;
    private Integer quantity;

    CartHeader(CartService cartService, Authentication authentication, HttpServletRequest request) {
        this.cartService = cartService;
        this.authentication = authentication;
        this.request = request;
    }

    /**
     * Total quantity of the current owner's cart; 0 when there is no request. The count is decorative, so a
     * failing query is logged and shown as 0 instead of breaking every page (including the error pages).
     */
    public int quantity() {
        if (quantity == null) {
            quantity = request == null ? 0 : countSafely();
        }
        return quantity;
    }

    private int countSafely() {
        try {
            return cartService.totalQuantity(CartOwners.resolve(authentication, request));
        } catch (RuntimeException e) {
            log.warn("Cart header count failed; showing 0", e);
            return 0;
        }
    }
}
