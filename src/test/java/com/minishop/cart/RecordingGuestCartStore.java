package com.minishop.cart;

import java.util.Optional;

/**
 * Test double của {@link GuestCartStore}: giỏ nằm trong một trường, có đếm số lần {@code getOrCreate},
 * {@code save} và {@code clear} để test khẳng định "không tạo session" (AC-004-5, 6, 14, 22).
 */
final class RecordingGuestCartStore implements GuestCartStore {

    private SessionCart cart;
    private int getOrCreateCalls;
    private int saveCalls;
    private int clearCalls;

    /** Dựng sẵn dữ liệu cho test, không tính vào bộ đếm. */
    RecordingGuestCartStore seed(Long productId, int quantity) {
        if (cart == null) {
            cart = new SessionCart();
        }
        cart.put(productId, quantity);
        return this;
    }

    @Override
    public Optional<SessionCart> find() {
        return Optional.ofNullable(cart);
    }

    @Override
    public SessionCart getOrCreate() {
        getOrCreateCalls++;
        if (cart == null) {
            cart = new SessionCart();
        }
        return cart;
    }

    @Override
    public void save(SessionCart cart) {
        saveCalls++;
        this.cart = cart;
    }

    @Override
    public void clear() {
        clearCalls++;
        cart = null;
    }

    int getOrCreateCalls() {
        return getOrCreateCalls;
    }

    int saveCalls() {
        return saveCalls;
    }

    int clearCalls() {
        return clearCalls;
    }
}
