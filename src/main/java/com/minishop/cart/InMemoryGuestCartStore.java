package com.minishop.cart;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The guest cart of the desktop app, kept in the memory of the process (BR-009-2: it is lost when the app closes).
 *
 * <p>One store for the whole process is enough: only one person sits in front of the app, so there is never more
 * than one guest at a time. It is a singleton bean, and {@code CurrentCartOwner} is the only place that hands it out
 * as the owner of a cart.
 *
 * <p><b>Threads:</b> services run on background threads, so the reference is an {@link AtomicReference}.
 * {@link #getOrCreate()} uses {@code updateAndGet}, so two threads that write at the same time still get the same
 * cart. {@link SessionCart} itself is {@code synchronized}.
 */
@Component
final class InMemoryGuestCartStore implements GuestCartStore {

    private final AtomicReference<SessionCart> cart = new AtomicReference<>();

    /** Read only: never creates a cart. */
    @Override
    public Optional<SessionCart> find() {
        return Optional.ofNullable(cart.get());
    }

    @Override
    public SessionCart getOrCreate() {
        return cart.updateAndGet(existing -> existing != null ? existing : new SessionCart());
    }

    @Override
    public void save(SessionCart updated) {
        cart.set(updated);
    }

    @Override
    public void clear() {
        cart.set(null);
    }
}
