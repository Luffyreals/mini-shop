package com.minishop.config;

import org.springframework.security.core.AuthenticationException;

/**
 * Passed to {@link LoginFailureHandler} by {@link LoginThrottleFilter} when a login attempt is refused before the
 * password is checked. The message is never shown to the user; the handler only uses the type to pick
 * {@code /login?blocked}.
 */
final class LoginBlockedException extends AuthenticationException {

    LoginBlockedException() {
        super("Login temporarily blocked");
    }
}
