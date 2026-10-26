package com.minishop.common;

/**
 * The caller is not allowed to do this: an admin operation was called by a guest or by a customer (AC-009-9).
 * Thrown by {@link AdminAccess#requireAdmin()} before anything is locked or written, so a refused call changes no data.
 *
 * <p>The message is a fixed English text for the log only (no email, no name); the screen picks the Vietnamese text.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException() {
        super("Admin role required");
    }
}
