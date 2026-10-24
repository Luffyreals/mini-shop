package com.minishop.cart;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Optional;

/** {@link GuestCartStore} backed by the {@code HttpSession} of one request. */
final class HttpSessionGuestCartStore implements GuestCartStore {

    private final HttpServletRequest request;

    HttpSessionGuestCartStore(HttpServletRequest request) {
        this.request = request;
    }

    /** Uses {@code getSession(false)}: reading never creates a session (so no JSESSIONID cookie for guests). */
    @Override
    public Optional<SessionCart> find() {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.getAttribute(SessionCart.SESSION_ATTRIBUTE))
                .filter(SessionCart.class::isInstance)
                .map(SessionCart.class::cast);
    }

    @Override
    public SessionCart getOrCreate() {
        HttpSession session = request.getSession(true);
        if (session.getAttribute(SessionCart.SESSION_ATTRIBUTE) instanceof SessionCart existing) {
            return existing;
        }
        SessionCart cart = new SessionCart();
        session.setAttribute(SessionCart.SESSION_ATTRIBUTE, cart);
        return cart;
    }

    @Override
    public void save(SessionCart cart) {
        request.getSession(true).setAttribute(SessionCart.SESSION_ATTRIBUTE, cart);
    }

    @Override
    public void clear() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SessionCart.SESSION_ATTRIBUTE);
        }
    }
}
