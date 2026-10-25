package com.minishop.user;

import com.minishop.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-008-3, BR-008-2 on the real PostgreSQL: the public mode resets the password of exactly the two demo accounts and
 * of nobody else. Same slice shape as {@code DataInitializerTest}. {@code PasswordEncoderConfig} is package-private in
 * {@code config}, so the delegating encoder it builds (the same factory call) is declared here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, DemoAccountService.class, DemoAccountServiceTest.EncoderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class DemoAccountServiceTest {

    private static final String NEW_ADMIN = "New-Admin-Pass-2026";
    private static final String NEW_USER = "New-User-Pass-2026";

    @TestConfiguration(proxyBeanMethods = false)
    static class EncoderConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }

    @Autowired
    DemoAccountService service;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private String otherHash;

    @BeforeEach
    void emptyDatabase() {
        users.deleteAllInBatch();
    }

    private void seedThreeAccounts() {
        users.save(new User(DemoAccountService.ADMIN_EMAIL, encoder.encode("Admin@123"), "Quản trị viên", Role.ADMIN));
        users.save(new User(DemoAccountService.USER_EMAIL, encoder.encode("User@123"), "Khách hàng mẫu", Role.USER));
        otherHash = encoder.encode("Other-pass-1");
        users.saveAndFlush(new User("khac@minishop.test", otherHash, "Người khác", Role.USER));
    }

    private String hashOf(String email) {
        return users.findByEmail(email).orElseThrow().getPasswordHash();
    }

    @Test
    void emails_areTheSeedAccounts() {
        assertThat(DemoAccountService.ADMIN_EMAIL).isEqualTo("admin@minishop.local");
        assertThat(DemoAccountService.USER_EMAIL).isEqualTo("user@minishop.local");
    }

    @Test
    void resetDemoPasswords_updatesExactlyTheTwoDemoAccountsAndReturnsTwo() {
        seedThreeAccounts();

        int updated = service.resetDemoPasswords(NEW_ADMIN, NEW_USER);

        assertThat(updated).isEqualTo(2);
        String adminHash = hashOf(DemoAccountService.ADMIN_EMAIL);
        String userHash = hashOf(DemoAccountService.USER_EMAIL);
        assertThat(adminHash).startsWith("{bcrypt}");
        assertThat(userHash).startsWith("{bcrypt}");
        assertThat(encoder.matches(NEW_ADMIN, adminHash)).isTrue();
        assertThat(encoder.matches(NEW_USER, userHash)).isTrue();
        assertThat(encoder.matches("Admin@123", adminHash)).as("the dev password no longer works").isFalse();
        assertThat(encoder.matches("User@123", userHash)).isFalse();
        assertThat(encoder.matches(NEW_USER, adminHash)).as("each account has its own password").isFalse();
        assertThat(encoder.matches(NEW_ADMIN, userHash)).isFalse();
    }

    @Test
    void resetDemoPasswords_neverTouchesAnotherAccountByteForByte() {
        seedThreeAccounts();

        service.resetDemoPasswords(NEW_ADMIN, NEW_USER);

        assertThat(hashOf("khac@minishop.test")).isEqualTo(otherHash);
        assertThat(encoder.matches("Other-pass-1", otherHash)).isTrue();
    }

    @Test
    void resetDemoPasswords_keepsRoleNameAndEmailOfTheDemoAccounts() {
        seedThreeAccounts();

        service.resetDemoPasswords(NEW_ADMIN, NEW_USER);

        User admin = users.findByEmail(DemoAccountService.ADMIN_EMAIL).orElseThrow();
        User user = users.findByEmail(DemoAccountService.USER_EMAIL).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getFullName()).isEqualTo("Quản trị viên");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getFullName()).isEqualTo("Khách hàng mẫu");
        assertThat(users.count()).isEqualTo(3);
    }

    @Test
    void resetDemoPasswords_onEmptyTable_returnsZeroAndCreatesNothing() {
        int updated = service.resetDemoPasswords(NEW_ADMIN, NEW_USER);

        assertThat(updated).isZero();
        assertThat(users.count()).isZero();
    }

    @Test
    void resetDemoPasswords_whenOnlyOneDemoAccountExists_updatesOnlyThatOne() {
        users.saveAndFlush(new User(DemoAccountService.USER_EMAIL, encoder.encode("User@123"), "Khách hàng mẫu",
                Role.USER));

        int updated = service.resetDemoPasswords(NEW_ADMIN, NEW_USER);

        assertThat(updated).isEqualTo(1);
        assertThat(encoder.matches(NEW_USER, hashOf(DemoAccountService.USER_EMAIL))).isTrue();
        assertThat(users.findByEmail(DemoAccountService.ADMIN_EMAIL)).isEmpty();
    }

    @Test
    void resetDemoPasswords_calledTwice_leavesTheLatestPasswords() {
        seedThreeAccounts();

        service.resetDemoPasswords(NEW_ADMIN, NEW_USER);
        int second = service.resetDemoPasswords("Second-Admin-Pass-1", "Second-User-Pass-1");

        assertThat(second).isEqualTo(2);
        assertThat(encoder.matches("Second-Admin-Pass-1", hashOf(DemoAccountService.ADMIN_EMAIL))).isTrue();
        assertThat(encoder.matches(NEW_ADMIN, hashOf(DemoAccountService.ADMIN_EMAIL))).isFalse();
    }

    @Test
    void resetDemoPasswords_blankOrNullPassword_throwsAndChangesNothing() {
        seedThreeAccounts();
        String adminBefore = hashOf(DemoAccountService.ADMIN_EMAIL);
        String userBefore = hashOf(DemoAccountService.USER_EMAIL);

        for (String[] pair : new String[][]{{null, NEW_USER}, {"", NEW_USER}, {"   ", NEW_USER},
                {NEW_ADMIN, null}, {NEW_ADMIN, ""}, {NEW_ADMIN, " \t"}, {null, null}}) {
            assertThatThrownBy(() -> service.resetDemoPasswords(pair[0], pair[1]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Demo password must not be blank");
        }

        assertThat(hashOf(DemoAccountService.ADMIN_EMAIL)).isEqualTo(adminBefore);
        assertThat(hashOf(DemoAccountService.USER_EMAIL)).isEqualTo(userBefore);
        assertThat(hashOf("khac@minishop.test")).isEqualTo(otherHash);
    }

    @Test
    void resetDemoPasswords_blankUserPassword_doesNotUpdateTheAdminEither() {
        seedThreeAccounts();
        String adminBefore = hashOf(DemoAccountService.ADMIN_EMAIL);

        assertThatThrownBy(() -> service.resetDemoPasswords(NEW_ADMIN, " ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(hashOf(DemoAccountService.ADMIN_EMAIL)).as("validated before any write").isEqualTo(adminBefore);
    }
}
