package com.minishop.common;

/**
 * The admin role check that a service can call without knowing who the session belongs to (ADR-0014, AC-009-9).
 *
 * <p>It lives in {@code common} because the admin services in {@code catalog} may not depend on {@code user}
 * (ArchUnit): {@code catalog} knows only this interface, and {@code user.AppSession} implements it (dependency
 * inversion). Hiding a menu entry is not a check; the service must call this itself.
 *
 * <p>From D5 every public method of {@code CategoryAdminService}, {@code ProductAdminService} and
 * {@code OrderAdminService} calls {@link #requireAdmin()} <b>first</b>, before any lock and before any write, so a
 * refused call changes nothing by construction. The returned id replaces the old {@code adminId} parameter.
 */
public interface AdminAccess {

    /**
     * Returns the id of the logged-in admin.
     *
     * @throws AccessDeniedException when nobody is logged in or the user is not an admin
     */
    long requireAdmin();
}
