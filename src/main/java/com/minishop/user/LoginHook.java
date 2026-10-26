package com.minishop.user;

/**
 * Work another feature wants done at login, for example {@code cart} merging the guest cart into the database cart
 * (BR-004-7). {@code user} defines the hook and the other feature implements it, so the dependency stays
 * {@code cart -> user} and {@code user} never knows {@code cart}.
 *
 * <p><b>When and where:</b> on a background thread, <b>outside any transaction</b>, after the password was accepted
 * and <b>before</b> the session changes. A hook that opens its own transaction therefore commits it before it
 * returns, and anything that reads the data when the session changes sees the result.
 *
 * <p><b>Failure:</b> a hook may throw. {@code AuthService} logs one WARN line (without personal data or SQL
 * messages), skips that hook and still reports a successful login. Implementations must leave their own data
 * consistent when they throw (the guest cart, for instance, is kept).
 *
 * <p>Hooks run in {@code @Order} order.
 */
public interface LoginHook {

    /** Called once for every successful login with the user who just logged in. */
    void afterLogin(CurrentUser user);
}
