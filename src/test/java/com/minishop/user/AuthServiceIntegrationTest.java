package com.minishop.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthService} on the real context and H2 (seed, {@link UserService}, hooks, transactions). Not
 * {@code @Transactional}: the point is what happens when no transaction (or a caller's one) is around. Test emails end
 * with {@code @auth.test} and are removed after each test; the seed accounts are only read.
 */
@SpringBootTest
class AuthServiceIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ProbeHook probe;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Value("${app.seed.user-password}")
    String userPassword;

    @BeforeEach
    void setUp() {
        probe.reset();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        probe.reset();
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from users where email like '%@auth.test'");
    }

    /** Records what a hook can see, and optionally does something extra. */
    static final class ProbeHook implements LoginHook {

        final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        final List<CurrentUser> users = new CopyOnWriteArrayList<>();
        volatile Consumer<CurrentUser> extra = user -> {
        };

        @Override
        public void afterLogin(CurrentUser user) {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            users.add(user);
            extra.accept(user);
        }

        void reset() {
            transactionActive.clear();
            users.clear();
            extra = user -> {
            };
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeHook probeHook() {
            return new ProbeHook();
        }
    }

    // ------------------------------------------------------------------ seed accounts

    @Test
    void login_withTheSeededAdminAccount_succeedsAsAdmin() {
        LoginResult result = authService.login(new LoginForm(DemoAccounts.ADMIN_EMAIL, adminPassword));

        assertThat(result).isInstanceOfSatisfying(LoginResult.Success.class, success -> {
            assertThat(success.user().email()).isEqualTo(DemoAccounts.ADMIN_EMAIL);
            assertThat(success.user().role()).isEqualTo(Role.ADMIN);
            assertThat(success.user().id()).isEqualTo(users.findByEmail(DemoAccounts.ADMIN_EMAIL).orElseThrow().getId());
        });
    }

    @Test
    void login_withTheSeededUserAccount_succeedsAsUser_andWithAWrongPasswordFails() {
        LoginResult ok = authService.login(new LoginForm(DemoAccounts.USER_EMAIL, userPassword));
        LoginResult wrong = authService.login(new LoginForm(DemoAccounts.USER_EMAIL, userPassword + "x"));
        LoginResult adminPasswordOnUser = authService.login(new LoginForm(DemoAccounts.USER_EMAIL, adminPassword));

        assertThat(ok).isInstanceOfSatisfying(LoginResult.Success.class,
                success -> assertThat(success.user().role()).isEqualTo(Role.USER));
        assertThat(wrong).isEqualTo(new LoginResult.InvalidCredentials());
        assertThat(adminPasswordOnUser).isEqualTo(new LoginResult.InvalidCredentials());
    }

    // ------------------------------------------------------------------ registration and login agree on the email (AC-003-3, AC-003-12)

    @Test
    void register_thenLogin_worksWithAnyCaseAndSurroundingSpaces() {
        Long id = userService.register(new RegistrationForm("An Nguyễn", "  An@Auth.TEST ", "Passw0rd123", "Passw0rd123"));

        for (String typed : List.of("an@auth.test", "AN@auth.test", "  An@Auth.TEST ")) {
            LoginResult result = authService.login(new LoginForm(typed, "Passw0rd123"));

            assertThat(result).as(typed).isEqualTo(new LoginResult.Success(
                    new CurrentUser(id, "an@auth.test", "An Nguyễn", Role.USER)));
        }
        assertThat(authService.login(new LoginForm("an@auth.test", "passw0rd123")))
                .as("the password is case sensitive").isEqualTo(new LoginResult.InvalidCredentials());
    }

    // ------------------------------------------------------------------ transactions (QĐ3)

    @Test
    void login_runsTheHooksOutsideAnyTransaction() {
        LoginResult result = authService.login(new LoginForm(DemoAccounts.USER_EMAIL, userPassword));

        assertThat(result).isInstanceOf(LoginResult.Success.class);
        assertThat(probe.transactionActive).containsExactly(false);
    }

    @Test
    void login_calledInsideACallersTransaction_suspendsItSoTheHooksStillRunOutsideATransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        LoginResult result = outer.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return authService.login(new LoginForm(DemoAccounts.USER_EMAIL, userPassword));
        });

        assertThat(result).isInstanceOf(LoginResult.Success.class);
        assertThat(probe.transactionActive).containsExactly(false);
    }

    /**
     * The reason for NOT_SUPPORTED: a hook that fails inside its own transaction must not poison the caller's. With a
     * joined transaction the outer commit would throw UnexpectedRollbackException although the login itself is fine.
     */
    @Test
    void login_whenAHooksOwnTransactionRollsBack_stillReturnsSuccessAndTheCallersTransactionCommits() {
        probe.extra = user -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            throw new IllegalStateException("merge failed");
        });
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        LoginResult result = outer.execute(status -> authService.login(new LoginForm(DemoAccounts.USER_EMAIL, userPassword)));

        assertThat(result).isInstanceOf(LoginResult.Success.class);
        assertThat(probe.users).hasSize(1);
    }
}
