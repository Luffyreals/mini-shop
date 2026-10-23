package com.minishop.user;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The authenticated principal stored in the HTTP session.
 *
 * <p>It is deliberately <b>not a record</b>: after a successful login Spring Security calls
 * {@link #eraseCredentials()} so the password hash never reaches the session (BR-003-4), and a record
 * is immutable and cannot clear a component. It is also not the {@link User} entity, so the session
 * holds no persistence state.
 *
 * <p>It is <b>public</b>, unlike most classes here, because it is the {@code user} module's contract
 * for "who is logged in": other features take {@code @AuthenticationPrincipal CurrentUser} to get the
 * id, and tests in other packages build it. The constructor takes plain values only, never an entity.
 *
 * <p>{@code equals}/{@code hashCode} use the email, like Spring's own {@code User}; {@code toString}
 * never prints the password.
 */
public final class CurrentUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String fullName;
    private final Role role;
    private String passwordHash;

    public CurrentUser(Long id, String email, String passwordHash, String fullName, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof CurrentUser other && Objects.equals(email, other.email));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(email);
    }

    @Override
    public String toString() {
        return "CurrentUser[id=" + id + ", email=" + email + ", role=" + role + "]";
    }
}
