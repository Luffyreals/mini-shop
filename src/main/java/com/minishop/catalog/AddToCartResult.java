package com.minishop.catalog;

/**
 * Outcome of {@link AddToCart#add}. The cart wording lives in {@code cart}, the product wording in
 * {@code catalog}, each in one place.
 */
public enum AddToCartResult {

    /**
     * The line was added or its quantity increased (possibly capped by stock or the 99 limit). The cart
     * implementation has already shown the success or "capped" toast and refreshed the header count.
     */
    ADDED,

    /**
     * The product has no stock left, so nothing was added. The cart implementation has already shown the
     * toast; the caller may refresh its own stock display.
     */
    OUT_OF_STOCK,

    /**
     * The quantity was not a whole number from 1 to 99. The cart implementation has already shown
     * "Số lượng không hợp lệ."; nothing was added and no service was called.
     */
    INVALID_QUANTITY,

    /**
     * The product no longer exists or is no longer sold. The cart implementation shows no toast; the
     * <b>caller</b> shows "Không tìm thấy sản phẩm." and navigates away.
     */
    PRODUCT_GONE,

    /**
     * An unexpected error. The implementation has already reported it through the common error path (one log
     * line and the generic error dialog); the caller shows nothing more and only re-enables its controls.
     */
    FAILED
}
