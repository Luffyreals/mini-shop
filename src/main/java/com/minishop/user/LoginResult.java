package com.minishop.user;

/**
 * Outcome of {@link AuthService#login}. A wrong email or password is a normal outcome, not an error, so the caller
 * handles both branches with an exhaustive {@code switch} and only real failures reach the error handler.
 */
sealed interface LoginResult {

    /** The credentials matched; the caller changes the session. */
    record Success(CurrentUser user) implements LoginResult {
    }

    /** Unknown email, wrong password, or an empty or too long input; deliberately not told apart (BR-003-5). */
    record InvalidCredentials() implements LoginResult {
    }
}
