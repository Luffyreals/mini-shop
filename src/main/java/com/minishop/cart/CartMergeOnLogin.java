package com.minishop.cart;

import com.minishop.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Merges the guest (session) cart into the user's DB cart right after a successful form login.
 *
 * <p>Lives in {@code cart} (not {@code config}) so that {@code config} does not depend on {@code cart} and
 * the success handler (and its saved-request redirect, AC-003-10) stays untouched. The event is published
 * after the session id changed (attributes were carried over) and before the redirect.
 *
 * <p>Failures are swallowed on purpose (BR-004-7): by now the security context is already saved, so
 * throwing would show a 500 to a user who is in fact logged in. The guest cart is then kept in the session.
 */
@Component
class CartMergeOnLogin {

    private static final Logger log = LoggerFactory.getLogger(CartMergeOnLogin.class);

    private final CartService cartService;

    CartMergeOnLogin(CartService cartService) {
        this.cartService = cartService;
    }

    @EventListener
    void onLogin(InteractiveAuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof CurrentUser user)) {
            return;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        GuestCartStore store = new HttpSessionGuestCartStore(attributes.getRequest());
        SessionCart guest = store.find().orElse(null);
        if (guest == null) {
            return;
        }
        if (guest.isEmpty()) {
            store.clear();
            return;
        }
        try {
            cartService.mergeGuestCart(user.getId(), guest);   // the merge transaction commits before returning
            store.clear();
        } catch (RuntimeException e) {
            log.warn("Cart merge failed for user {}", user.getId(), e);
        }
    }
}
