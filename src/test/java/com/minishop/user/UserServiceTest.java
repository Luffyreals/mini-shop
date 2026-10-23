package com.minishop.user;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Đăng ký của {@link UserService}: chuẩn hóa email, luôn USER, băm mật khẩu, trùng email
 * (AC-003-2..5, BR-003-1, BR-003-3, QĐ5).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository users;

    PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, encoder);
    }

    private static RegistrationForm form(String email, String password) {
        return new RegistrationForm("An Nguyễn", email, password, password);
    }

    private User captureSaved() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private void stubSaveAssigningId(long id) {
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", id);
            return u;
        });
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        var cause = new ConstraintViolationException("dup", new SQLException("dup"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    @Test
    void register_normalizesEmailForLookupAndStorage() {
        stubSaveAssigningId(1L);

        service.register(form(" New@X.com ", "abcdefg1"));

        verify(users).existsByEmail("new@x.com");
        assertThat(captureSaved().getEmail()).isEqualTo("new@x.com");
    }

    @Test
    void register_savesRoleUserAndReturnsId() {
        stubSaveAssigningId(42L);

        Long id = service.register(form("an@example.com", "abcdefg1"));

        assertThat(id).isEqualTo(42L);
        User saved = captureSaved();
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getFullName()).isEqualTo("An Nguyễn");
    }

    @Test
    void register_hashesPasswordWithDelegatingBcrypt() {
        stubSaveAssigningId(1L);

        service.register(form("an@example.com", "abcdefg1"));

        String hash = captureSaved().getPasswordHash();
        assertThat(hash).startsWith("{bcrypt}").isNotEqualTo("abcdefg1");
        assertThat(encoder.matches("abcdefg1", hash)).isTrue();
    }

    @Test
    void register_whenEmailExists_throwsAndNeverSaves() {
        when(users.existsByEmail("an@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(form("An@Example.com", "abcdefg1")))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessageNotContaining("example.com");

        verify(users, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uq_users_email",
            "public.uq_users_email INDEX public.uq_users_email_INDEX_6",
            "UQ_USERS_EMAIL"})
    void register_whenRaceHitsUniqueConstraint_throwsEmailAlreadyRegistered(String constraintName) {
        DataIntegrityViolationException dive = violationOf(constraintName);
        when(users.saveAndFlush(any(User.class))).thenThrow(dive);

        assertThatThrownBy(() -> service.register(form("an@example.com", "abcdefg1")))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasCause(dive);
    }

    @Test
    void register_whenOtherConstraintViolated_rethrowsOriginal() {
        DataIntegrityViolationException dive = violationOf("ck_users_email_lowercase");
        when(users.saveAndFlush(any(User.class))).thenThrow(dive);

        assertThatThrownBy(() -> service.register(form("an@example.com", "abcdefg1")))
                .isSameAs(dive);
    }

    @Test
    void register_whenConstraintNameIsNullOrNoHibernateCause_rethrowsOriginal() {
        DataIntegrityViolationException nullName = violationOf(null);
        DataIntegrityViolationException noCause = new DataIntegrityViolationException("boom");
        when(users.saveAndFlush(any(User.class))).thenThrow(nullName).thenThrow(noCause);

        assertThatThrownBy(() -> service.register(form("an@example.com", "abcdefg1"))).isSameAs(nullName);
        assertThatThrownBy(() -> service.register(form("an@example.com", "abcdefg1"))).isSameAs(noCause);
    }
}
