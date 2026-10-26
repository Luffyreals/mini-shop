package com.minishop.user;

/**
 * Who is logged in, held by {@code AppSession} for the lifetime of the desktop session.
 *
 * <p>It is a plain record of values: never the {@link User} entity (no persistence state in the UI) and never the
 * password hash. It is <b>public</b>, unlike most classes here, because it is the {@code user} module's contract for
 * "who is logged in": other features take it to get the id and the role (ADR-0014).
 *
 * <p>{@code toString} prints only the id and the role, because the email and the full name are personal data.
 */
public record CurrentUser(Long id, String email, String fullName, Role role) {

    @Override
    public String toString() {
        return "CurrentUser[id=" + id + ", role=" + role + "]";
    }
}
