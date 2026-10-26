package com.minishop.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Đăng ký đầu-cuối trên H2 thật (service gọi trực tiếp, ADR-0014). Không dùng {@code @Transactional}: race cần các
 * transaction độc lập và commit thật. Email test kết thúc bằng {@code @reg.test} và bị xóa sau mỗi test; hai tài
 * khoản seed không bị đụng tới.
 */
@SpringBootTest
class RegistrationIntegrationTest {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where email like '%@reg.test'");
    }

    private int countUsers(String email) {
        return jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    private static RegistrationForm form(String name, String email) {
        return new RegistrationForm(name, email, PASSWORD, PASSWORD);
    }

    private static RegistrationForm form(String email) {
        return form("Race User", email);
    }

    /** AC-003-2 */
    @Test
    void register_validForm_storesUserWithRoleUserAndBcryptHash() {
        Long id = userService.register(form("Nguyen Van A", "valid@reg.test"));

        assertThat(countUsers("valid@reg.test")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from users where email = 'valid@reg.test'", Long.class))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("select role from users where email = 'valid@reg.test'", String.class))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("select password_hash from users where email = 'valid@reg.test'", String.class))
                .startsWith("{bcrypt}")
                .doesNotContain(PASSWORD);
    }

    /** AC-003-3, phần lưu: email được chuẩn hóa chữ thường, hash khớp mật khẩu qua PasswordEncoder.matches. */
    @Test
    void register_emailWithSpacesAndUpperCase_isStoredLowerCaseAndHashMatchesThePassword() {
        userService.register(form("An", "  An@Reg.TEST "));

        assertThat(countUsers("an@reg.test")).isEqualTo(1);
        String hash = jdbc.queryForObject("select password_hash from users where email = 'an@reg.test'", String.class);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD + "x", hash)).isFalse();
    }

    /**
     * AC-003-7: {@link RegistrationForm} không có thành phần role hay id (khẳng định ở
     * {@code RegistrationFormValidationTest#recordComponents_areExactlyFourAndHaveNoRole}), nên tài khoản đăng ký
     * luôn là USER; ở đây kiểm giá trị thật trong DB.
     */
    @Test
    void register_alwaysCreatesRoleUser() {
        userService.register(form("Hacker", "hacker@reg.test"));

        assertThat(jdbc.queryForObject("select role from users where email = 'hacker@reg.test'", String.class))
                .isEqualTo("USER");
    }

    /** AC-003-4 */
    @Test
    void register_duplicateEmailDifferentCase_throwsAndKeepsOneRow() {
        userService.register(form("An", "an@reg.test"));

        assertThatThrownBy(() -> userService.register(form("Other", "AN@reg.test")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        assertThat(countUsers("an@reg.test")).isEqualTo(1);
    }

    /**
     * AC-003-5 (a): cuộc đua chắc chắn. existsByEmail bị ép trả false nên lần đăng ký thứ hai chạm ràng buộc
     * unique của H2 thật; xác nhận việc so tên ràng buộc {@code uq_users_email} hoạt động trên H2.
     */
    @Test
    void register_whenExistsCheckMissesDuplicate_dbConstraintBecomesEmailAlreadyRegistered() {
        userService.register(form("forced@reg.test"));
        doReturn(false).when(userRepository).existsByEmail(anyString());

        assertThatThrownBy(() -> userService.register(form("forced@reg.test")))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(countUsers("forced@reg.test")).isEqualTo(1);
    }

    /** AC-003-5 (b): hai luồng đăng ký cùng email, 20 vòng; mỗi vòng đúng 1 thành công và 1 EmailAlreadyRegisteredException. */
    @Test
    void register_concurrentSameEmail_exactlyOneSucceedsPerRound() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String email = "race" + round + "@reg.test";
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Throwable>> futures = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        try {
                            userService.register(form(email));
                            return null;
                        } catch (Throwable e) {
                            return e;
                        }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                List<Throwable> outcomes = new ArrayList<>();
                for (Future<Throwable> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(outcomes.stream().filter(o -> o == null).count()).as("round %d successes", round).isEqualTo(1);
                assertThat(outcomes.stream().filter(o -> o != null).toList()).as("round %d failures", round)
                        .hasSize(1)
                        .allSatisfy(e -> assertThat(e).isInstanceOf(EmailAlreadyRegisteredException.class));
                assertThat(countUsers(email)).as("round %d rows", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
