package com.minishop.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nạp người dùng cho form login và {@link CurrentUser} (AC-003-9, AC-003-12, AC-003-16, BR-003-4, BR-003-5). */
@ExtendWith(MockitoExtension.class)
class ShopUserDetailsServiceTest {

    private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Mock
    UserRepository users;

    ShopUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new ShopUserDetailsService(users);
    }

    private static User user(long id, String email, Role role) {
        User u = new User(email, HASH, "Người Dùng", role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void loadUserByUsername_normalizesUsernameBeforeLookup() {
        when(users.findByEmail("user@minishop.local"))
                .thenReturn(Optional.of(user(1L, "user@minishop.local", Role.USER)));

        UserDetails details = service.loadUserByUsername("  USER@MiniShop.LOCAL ");

        verify(users).findByEmail("user@minishop.local");
        assertThat(details.getUsername()).isEqualTo("user@minishop.local");
    }

    @Test
    void loadUserByUsername_whenNotFound_throwsWithoutEmailInMessage() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("Ghost@Example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("ghost")
                .hasMessageNotContaining("example.com");
    }

    @Test
    void loadUserByUsername_whenFound_mapsIdEmailNameRoleAndHash() {
        when(users.findByEmail("user@minishop.local"))
                .thenReturn(Optional.of(user(7L, "user@minishop.local", Role.USER)));

        CurrentUser current = (CurrentUser) service.loadUserByUsername("user@minishop.local");

        assertThat(current.getId()).isEqualTo(7L);
        assertThat(current.getEmail()).isEqualTo("user@minishop.local");
        assertThat(current.getFullName()).isEqualTo("Người Dùng");
        assertThat(current.getRole()).isEqualTo(Role.USER);
        assertThat(current.getPassword()).isEqualTo(HASH);
        assertThat(current.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void loadUserByUsername_whenAdmin_hasRoleAdminAuthority() {
        when(users.findByEmail("admin@minishop.local"))
                .thenReturn(Optional.of(user(2L, "admin@minishop.local", Role.ADMIN)));

        UserDetails details = service.loadUserByUsername("admin@minishop.local");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void currentUser_eraseCredentials_nullsPasswordAndToStringHidesHash() {
        CurrentUser current = new CurrentUser(1L, "user@minishop.local", HASH, "Người Dùng", Role.USER);
        assertThat(current.toString()).doesNotContain(HASH).doesNotContain("bcrypt");

        current.eraseCredentials();

        assertThat(current.getPassword()).isNull();
        assertThat(current.toString()).doesNotContain("bcrypt");
        assertThat(current.getUsername()).isEqualTo("user@minishop.local");
    }

    @Test
    void currentUser_isEnabledAndNonExpired() {
        CurrentUser current = new CurrentUser(1L, "u@x.com", HASH, "U", Role.USER);

        assertThat(current.isEnabled()).isTrue();
        assertThat(current.isAccountNonLocked()).isTrue();
        assertThat(current.isAccountNonExpired()).isTrue();
        assertThat(current.isCredentialsNonExpired()).isTrue();
    }
}
