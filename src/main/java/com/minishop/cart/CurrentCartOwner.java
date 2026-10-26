package com.minishop.cart;

import com.minishop.user.AppSession;
import com.minishop.user.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * The one place that decides who owns the cart right now (the desktop version of BR-004-8): the logged-in user's
 * database cart, or the in-memory guest cart. The owner is never taken from a screen argument, so one person cannot
 * reach another person's cart.
 *
 * <p>Read the owner on the JavaFX thread and pass it to the background work, so the work does not read the session
 * a second time and see a different user.
 */
@Component
class CurrentCartOwner {

    private final AppSession session;
    private final GuestCartStore guestStore;

    CurrentCartOwner(AppSession session, GuestCartStore guestStore) {
        this.session = session;
        this.guestStore = guestStore;
    }

    /** {@code Customer(userId)} when someone is logged in, {@code Guest(store)} otherwise. */
    CartOwner get() {
        CurrentUser user = session.currentUser().orElse(null);
        if (user == null) {
            return new CartOwner.Guest(guestStore);
        }
        return new CartOwner.Customer(user.id());
    }
}
