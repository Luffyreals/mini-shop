package com.minishop.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Đăng ký đầu-cuối trên PostgreSQL thật. Không dùng {@code @Transactional}: race cần các transaction độc lập
 * và commit thật. Email test kết thúc bằng {@code @reg.test} và bị xóa sau mỗi test; hai tài khoản seed không bị đụng tới.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationIntegrationTest {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    @MockitoSpyBean
    UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where email like '%@reg.test'");
    }

    private int countUsers(String email) {
        return jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    private int countAllUsers() {
        return jdbc.queryForObject("select count(*) from users", Integer.class);
    }

    private org.springframework.test.web.servlet.ResultActions register(String name, String email) throws Exception {
        return mockMvc.perform(post("/register").with(csrf())
                .param("fullName", name)
                .param("email", email)
                .param("password", PASSWORD)
                .param("confirmPassword", PASSWORD));
    }

    private static RegistrationForm form(String email) {
        return new RegistrationForm("Race User", email, PASSWORD, PASSWORD);
    }

    /** AC-003-2 */
    @Test
    void register_validForm_redirectsToLoginAndStoresUserWithBcryptHash() throws Exception {
        register("Nguyen Van A", "valid@reg.test")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(countUsers("valid@reg.test")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users where email = 'valid@reg.test'", String.class))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("select password_hash from users where email = 'valid@reg.test'", String.class))
                .startsWith("{bcrypt}")
                .doesNotContain(PASSWORD);
    }

    /** AC-003-3 */
    @Test
    void register_emailWithSpacesAndUpperCase_isStoredLowerCaseAndCanLogIn() throws Exception {
        register("An", "  An@Reg.TEST ").andExpect(redirectedUrl("/login"));

        assertThat(countUsers("an@reg.test")).isEqualTo(1);
        mockMvc.perform(formLogin("/login").userParameter("email").user("an@reg.test").password(PASSWORD))
                .andExpect(authenticated());
    }

    /** AC-003-7 */
    @Test
    void register_withRoleAdminParameter_stillCreatesUser() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Hacker")
                        .param("email", "hacker@reg.test")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("role", "ADMIN"))
                .andExpect(redirectedUrl("/login"));

        assertThat(jdbc.queryForObject("select role from users where email = 'hacker@reg.test'", String.class))
                .isEqualTo("USER");
    }

    /** AC-003-4 */
    @Test
    void register_duplicateEmailDifferentCase_showsErrorAndKeepsOneRow() throws Exception {
        register("An", "an@reg.test").andExpect(redirectedUrl("/login"));

        register("Other", "AN@reg.test")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email này đã được đăng ký.")));

        assertThat(countUsers("an@reg.test")).isEqualTo(1);
    }

    /** AC-003-18 */
    @Test
    void register_withoutCsrf_isForbiddenAndCreatesNothing() throws Exception {
        int before = countAllUsers();

        mockMvc.perform(post("/register")
                        .param("fullName", "No Csrf")
                        .param("email", "nocsrf@reg.test")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(countAllUsers()).isEqualTo(before);
        assertThat(countUsers("nocsrf@reg.test")).isZero();
    }

    /** AC-003-21 end-to-end: tên chứa HTML được escape trên trang chủ sau khi đăng nhập. */
    @Test
    void register_nameWithScript_isEscapedInHeaderAfterLogin() throws Exception {
        register("<script>alert(1)</script>", "xss@reg.test").andExpect(redirectedUrl("/login"));

        MockHttpSession session = (MockHttpSession) mockMvc
                .perform(formLogin("/login").userParameter("email").user("xss@reg.test").password(PASSWORD))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();

        String html = mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Xin chào, &lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    /**
     * AC-003-5 (a): cuộc đua chắc chắn. existsByEmail bị ép trả false nên lần đăng ký thứ hai chạm ràng buộc
     * unique của PostgreSQL thật; xác nhận việc so tên ràng buộc {@code uq_users_email} hoạt động trên PostgreSQL.
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
