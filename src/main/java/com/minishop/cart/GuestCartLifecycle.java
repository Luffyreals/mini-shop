package com.minishop.cart;

import com.minishop.user.CurrentUser;
import com.minishop.user.LoginHook;
import com.minishop.user.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Owns what happens to the guest cart when the session changes (spec 004, BR-004-7, ADR-0006; ADR-0014).
 *
 * <ul>
 *   <li><b>Login ({@link LoginHook}):</b> merges the guest cart into the user's database cart, then empties the
 *       guest cart. It runs on a background thread, before the session changes, so the cart badge reads the merged
 *       cart.</li>
 *   <li><b>Logout ({@link SessionListener}):</b> empties the guest cart, so the next guest starts with nothing
 *       (AC-009-10). Normally it is already empty after the merge; this clears what a failed merge left behind.</li>
 * </ul>
 *
 * <p><b>Why there is no {@code @Transactional} here, and why the order matters:</b> the merge goes through the
 * {@code CartService} proxy, so its transaction commits <b>before</b> {@code mergeGuestCart} returns. Only then is
 * the guest cart cleared. If this class had a transaction, the merge would join it and the guest cart could be
 * cleared before the data is committed, or a failed merge would mark the outer transaction rollback-only.
 *
 * <p><b>Failure (BR-004-7):</b> when the merge throws, the exception is <b>not</b> caught here and the guest cart is
 * <b>not</b> cleared, so the person loses nothing. {@code AuthService} catches the exception, logs one WARN line
 * and the login still succeeds (by then the password was already accepted, so failing the login would punish a user
 * who is in fact valid). While logged in, the cart owner is the customer, so the kept guest cart is not shown; it is
 * cleared on logout, like the web version where logout ended the HTTP session. The person is not told that the
 * merge failed (only the WARN line); a notice would need new copy (plan D2, section 8).
 *
 * <p>Runs first among the session listeners, so a presenter that reads the cart count sees an empty guest cart
 * after logout.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GuestCartLifecycle implements LoginHook, SessionListener {

    private static final Logger log = LoggerFactory.getLogger(GuestCartLifecycle.class);

    private final CartService cartService;
    private final GuestCartStore store;

    GuestCartLifecycle(CartService cartService, GuestCartStore store) {
        this.cartService = cartService;
        this.store = store;
    }

    @Override
    public void afterLogin(CurrentUser user) {
        SessionCart guest = store.find().orElse(null);
        if (guest == null) {
            return;
        }
        if (guest.isEmpty()) {
            store.clear();
            return;
        }
        cartService.mergeGuestCart(user.id(), guest);   // the merge transaction commits before returning
        store.clear();
        log.debug("Guest cart merged for user {}", user.id());
    }

    @Override
    public void sessionChanged(CurrentUser previous, CurrentUser current) {
        if (current == null) {
            store.clear();
        }
    }
}
