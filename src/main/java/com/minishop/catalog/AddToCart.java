package com.minishop.catalog;

import java.util.function.Consumer;

/**
 * "Add to cart" as the storefront screens need it. {@code catalog} owns the product screens but may not depend on
 * {@code cart}, so {@code catalog} defines this interface and {@code cart} implements it (the same inversion as
 * {@code user.LoginHook}): the dependency stays {@code cart -> catalog}.
 *
 * <p><b>Threading:</b> {@link #add} is called on the JavaFX thread. The implementation reads the cart owner on that
 * thread before it submits any work, does the work in the background, and calls {@code onFinished} <b>exactly once,
 * on the JavaFX thread</b>, whatever the outcome. The caller must not block in {@code onFinished} and should
 * disable the control that started the add until it runs.
 *
 * <p><b>Messages:</b> the implementation shows the toasts about the cart itself (added, quantity capped, bad
 * quantity). The caller only reacts to the result, see {@link AddToCartResult} for who shows what.
 */
public interface AddToCart {

    /**
     * Adds {@code rawQuantity} units of the product to the current cart (guest or signed in).
     *
     * @param productId   id of the product to add
     * @param rawQuantity the quantity exactly as the user typed it; the implementation parses and validates it
     * @param onFinished  called exactly once on the JavaFX thread with the outcome
     */
    void add(long productId, String rawQuantity, Consumer<AddToCartResult> onFinished);
}
