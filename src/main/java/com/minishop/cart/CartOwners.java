package com.minishop.cart;

import com.minishop.user.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

/** Decides who owns the cart of the current request: only from the principal or the session, never from input. */
final class CartOwners {

    private CartOwners() {
    }

    static CartOwner resolve(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return new CartOwner.Customer(user.getId());
        }
        return new CartOwner.Guest(new HttpSessionGuestCartStore(request));
    }
}
