package com.minishop.order;

/** Outcome of a customer cancel request; a missing or foreign order is a {@code NotFoundException} instead. */
enum CancelResult {
    CANCELLED,
    NOT_CANCELLABLE
}
