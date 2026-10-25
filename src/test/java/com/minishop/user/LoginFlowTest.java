package com.minishop.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Luồng đăng nhập/đăng xuất đầu-cuối với tài khoản seed trên PostgreSQL thật. Các ca cũ chỉ đọc.
 * Không dùng @MockitoSpyBean để dùng chung context với các test tích hợp khác.
 *
 * <p>GĐ8 (giới hạn đăng nhập sai, AC-008-7, 9, 10): bộ đếm sống cùng Spring context, mà context này được dùng chung
 * (QĐ10). Vì vậy các ca mới chỉ làm đăng nhập sai với tài khoản riêng ({@code throttle-<uuid>@minishop.test}, tạo bằng
 * JDBC, xóa ở {@code finally}) hoặc email không tồn tại, và từ địa chỉ riêng {@code 192.0.2.x} (MockMvc
 * {@code setRemoteAddr}); không bao giờ thêm lần sai cho {@code user@}/{@code admin@minishop.local} hay quá 19 lần
 * từ một địa chỉ.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginFlowTest {

    private static final String USER_EMAIL = "user@minishop.local";
    private static final String SESSION_CONTEXT = "SPRING_SECURITY_CONTEXT";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Value("${app.seed.user-password}")
    String userPassword;

    @Value("${app.seed.admin-password}")
    String adminPassword;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private MvcResult login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(formLoginIn(email, password, session)).andReturn();
    }

    /** FormLoginRequestBuilder has no session(...), so wrap it and attach the session to the built request. */
    private static RequestBuilder formLoginIn(String email, String password, MockHttpSession session) {
        RequestBuilder login = formLogin("/login").userParameter("email").user(email).password(password);
        return context -> {
            MockHttpServletRequest request = login.buildRequest(context);
            request.setSession(session);
            return request;
        };
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static Authentication authenticationOf(MockHttpSession session) {
        SecurityContext ctx = (SecurityContext) session.getAttribute(SESSION_CONTEXT);
        assertThat(ctx).isNotNull();
        return ctx.getAuthentication();
    }

    private static java.util.List<String> authorities(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /** AC-003-9 */
    @Test
    void login_seedUser_redirectsHomeWithRoleUserAndGreetsByName() throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/login").userParameter("email")
                        .user(USER_EMAIL).password(userPassword))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = sessionOf(result);
        assertThat(session).isNotNull();
        assertThat(authorities(authenticationOf(session))).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Xin chào, Khách hàng mẫu")))
                .andExpect(content().string(containsString("Đăng xuất")));
    }

    /** AC-003-9 cho admin: authority ROLE_ADMIN. */
    @Test
    void login_seedAdmin_hasRoleAdmin() throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/login").userParameter("email")
                        .user("admin@minishop.local").password(adminPassword))
                .andExpect(authenticated())
                .andReturn();
        assertThat(authorities(authenticationOf(sessionOf(result)))).contains("ROLE_ADMIN");
    }

    /** AC-003-10 */
    @Test
    void login_afterAnonymousVisitToOrders_redirectsBackToOrders() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/orders").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(URI.create(result.getResponse().getRedirectedUrl()).getPath())
                        .isEqualTo("/login"));

        MvcResult result = login(USER_EMAIL, userPassword, session);

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String target = result.getResponse().getRedirectedUrl();
        assertThat(target).isNotNull();
        assertThat(URI.create(target).getPath()).startsWith("/orders");
    }

    /** AC-003-11, BR-003-5 */
    @Test
    void login_wrongPasswordAndUnknownEmail_giveSameRedirectAndIdenticalPage() throws Exception {
        String wrongPasswordPage = failedLoginPage(USER_EMAIL, "Wrong-pass-1");
        String unknownEmailPage = failedLoginPage("nobody@minishop.local", "Wrong-pass-1");

        assertThat(wrongPasswordPage).contains("Email hoặc mật khẩu không đúng.")
                .contains("value=\"" + USER_EMAIL + "\"");
        assertThat(unknownEmailPage).contains("Email hoặc mật khẩu không đúng.")
                .contains("value=\"nobody@minishop.local\"");
        assertThat(normalize(wrongPasswordPage)).isEqualTo(normalize(unknownEmailPage));
    }

    /** Thực hiện đăng nhập sai, assert redirect /login?error, rồi trả về HTML của trang đăng nhập cùng session. */
    private String failedLoginPage(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(formLoginIn(email, password, session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        // URL thật có query string: .param() không tạo query string nên không bắt được lỗi permitAll của /login?error.
        return mockMvc.perform(get("/login?error").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Bỏ mọi value="..." (có email và token CSRF) để so sánh phần còn lại. */
    private static String normalize(String html) {
        return html.replaceAll("value=\"[^\"]*\"", "value=\"\"");
    }

    /** QĐ2: mật khẩu vượt 72 byte không gây 500. */
    @Test
    void login_passwordLongerThan72Bytes_failsGracefully() throws Exception {
        mockMvc.perform(formLogin("/login").userParameter("email").user(USER_EMAIL).password("a1".repeat(50)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    /** AC-003-12 */
    @Test
    void login_emailWithSpacesAndUpperCase_succeeds() throws Exception {
        mockMvc.perform(formLogin("/login").userParameter("email").user("  USER@MiniShop.LOCAL ")
                        .password(userPassword))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated());
    }

    /** AC-003-13 */
    @Test
    void logout_post_invalidatesSessionAndRedirectsWithMessage() throws Exception {
        MockHttpSession session = sessionOf(login(USER_EMAIL, userPassword, new MockHttpSession()));
        assertThat(session).isNotNull();

        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?logout"));

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/?logout"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bạn đã đăng xuất.")));
    }

    /** AC-003-13: GET /logout không đăng xuất. */
    @Test
    void logout_get_doesNotLogOut() throws Exception {
        MockHttpSession session = sessionOf(login(USER_EMAIL, userPassword, new MockHttpSession()));
        assertThat(session).isNotNull();

        MvcResult result = mockMvc.perform(get("/logout").session(session)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNotEqualTo("/?logout");

        assertThat(session.isInvalid()).isFalse();
        mockMvc.perform(get("/").session(session))
                .andExpect(authenticated())
                .andExpect(content().string(containsString("Xin chào, Khách hàng mẫu")));
    }

    /** AC-003-19: chống session fixation. */
    @Test
    void login_changesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/login").session(session)).andExpect(status().isOk());
        String before = session.getId();

        MockHttpSession after = sessionOf(login(USER_EMAIL, userPassword, session));

        assertThat(after).isNotNull();
        assertThat(after.getId()).isNotEqualTo(before);
    }

    /** AC-003-20: không có mật khẩu sinh tự động, UserDetailsService duy nhất là của ứng dụng. */
    @Test
    void context_hasOnlyShopUserDetailsService() {
        Map<String, UserDetailsService> beans = context.getBeansOfType(UserDetailsService.class);

        assertThat(beans).hasSize(1);
        assertThat(beans.values()).allSatisfy(b -> assertThat(b)
                .isInstanceOf(ShopUserDetailsService.class)
                .isNotInstanceOf(InMemoryUserDetailsManager.class));
    }

    // ------------------------------------------------------------------ GĐ8: giới hạn đăng nhập sai

    private static final String BLOCKED_TEXT = "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.";
    private static final String WRONG_TEXT = "Email hoặc mật khẩu không đúng.";
    private static final String ACCOUNT_PASSWORD = "Throttle-pass-1";

    /** Đăng nhập từ một địa chỉ riêng (và tùy chọn session), như formLoginIn nhưng đặt remoteAddr. */
    private static RequestBuilder attempt(String ip, String email, String password, MockHttpSession session) {
        RequestBuilder login = formLogin("/login").userParameter("email").user(email).password(password);
        return context -> {
            MockHttpServletRequest request = login.buildRequest(context);
            request.setRemoteAddr(ip);
            request.setSession(session);
            return request;
        };
    }

    private static String uniqueEmail() {
        return "throttle-" + UUID.randomUUID() + "@minishop.test";
    }

    private String createAccount() {
        String email = uniqueEmail();
        jdbc.update("insert into users (email, password_hash, full_name, role) values (?, ?, 'Throttle Tester', 'USER')",
                email, encoder.encode(ACCOUNT_PASSWORD));
        return email;
    }

    private void deleteAccount(String email) {
        jdbc.update("delete from users where email = ?", email);
    }

    private void wrongAttempts(String ip, String email, MockHttpSession session, int times) throws Exception {
        for (int i = 1; i <= times; i++) {
            mockMvc.perform(attempt(ip, email, "Wrong-pass-" + i, session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"))
                    .andExpect(unauthenticated());
        }
    }

    /** Trang /login?blocked của session đã bị chặn, sau khi đăng nhập thứ sáu (mật khẩu {@code password}). */
    private String blockedPage(String ip, String email, String password, MockHttpSession session) throws Exception {
        mockMvc.perform(attempt(ip, email, password, session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?blocked"))
                .andExpect(unauthenticated());
        // URL thật có query string (bài học GĐ3): .param() không tạo query string.
        return mockMvc.perform(get("/login?blocked").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** AC-008-7: 5 lần sai, lần thứ 6 dù đúng mật khẩu vẫn bị chặn và không đăng nhập. */
    @Test
    void login_afterFiveWrongPasswords_theSixthWithTheCorrectPasswordIsBlockedAndNotAuthenticated() throws Exception {
        String email = createAccount();
        try {
            MockHttpSession session = new MockHttpSession();
            wrongAttempts("192.0.2.10", email, session, 5);

            String page = blockedPage("192.0.2.10", email, ACCOUNT_PASSWORD, session);

            assertThat(page).contains(BLOCKED_TEXT).doesNotContain(WRONG_TEXT)
                    .contains("value=\"" + email + "\"");
            assertThat(page).contains("role=\"alert\"");
        } finally {
            deleteAccount(email);
        }
    }

    /** AC-008-7: email có và không có tài khoản cho cùng phản hồi (câu hỏi 5), cùng ngưỡng. */
    @Test
    void login_blockedPage_isIdenticalForAnExistingAndAnUnknownEmail() throws Exception {
        String existing = createAccount();
        String unknown = uniqueEmail();
        try {
            MockHttpSession existingSession = new MockHttpSession();
            wrongAttempts("192.0.2.11", existing, existingSession, 5);
            String existingPage = blockedPage("192.0.2.11", existing, "Wrong-pass-6", existingSession);

            MockHttpSession unknownSession = new MockHttpSession();
            wrongAttempts("192.0.2.12", unknown, unknownSession, 5);
            String unknownPage = blockedPage("192.0.2.12", unknown, "Wrong-pass-6", unknownSession);

            assertThat(existingPage).contains(BLOCKED_TEXT).contains("value=\"" + existing + "\"");
            assertThat(unknownPage).contains(BLOCKED_TEXT).contains("value=\"" + unknown + "\"");
            assertThat(normalize(existingPage)).isEqualTo(normalize(unknownPage));
        } finally {
            deleteAccount(existing);
        }
    }

    /** AC-008-7: người đang bị chặn thấy chữ bị chặn, người chỉ sai một lần thấy chữ sai mật khẩu. */
    @Test
    void login_aSingleWrongPassword_showsTheWrongPasswordTextNotTheBlockedOne() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = new MockHttpSession();
        wrongAttempts("192.0.2.13", email, session, 1);

        String page = mockMvc.perform(get("/login?error").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains(WRONG_TEXT).doesNotContain(BLOCKED_TEXT);
    }

    /** AC-008-9: 3 lần sai rồi đúng thì đăng nhập được và bộ đếm của email về 0. */
    @Test
    void login_afterThreeWrongPasswordsThenTheCorrectOne_succeedsAndTheCounterRestarts() throws Exception {
        String email = createAccount();
        try {
            MockHttpSession session = new MockHttpSession();
            wrongAttempts("192.0.2.14", email, session, 3);

            MvcResult success = mockMvc.perform(attempt("192.0.2.14", email, ACCOUNT_PASSWORD, session))
                    .andExpect(redirectedUrl("/"))
                    .andExpect(authenticated())
                    .andReturn();
            MockHttpSession loggedIn = sessionOf(success);
            assertThat(loggedIn).isNotNull();
            mockMvc.perform(post("/logout").with(csrf()).session(loggedIn))
                    .andExpect(redirectedUrl("/?logout"));

            MockHttpSession next = new MockHttpSession();
            wrongAttempts("192.0.2.14", email, next, 4);
            // the 5th wrong attempt is still an ordinary failure, not "blocked": the count restarted at the success
            mockMvc.perform(attempt("192.0.2.14", email, "Wrong-pass-5", next))
                    .andExpect(redirectedUrl("/login?error"));
            // ... and it was counted: the 6th is refused
            mockMvc.perform(attempt("192.0.2.14", email, ACCOUNT_PASSWORD, next))
                    .andExpect(redirectedUrl("/login?blocked"))
                    .andExpect(unauthenticated());
        } finally {
            deleteAccount(email);
        }
    }

    /** AC-008-9: một đăng nhập đúng không xóa các lần sai của cùng địa chỉ IP (BR-008-3). */
    @Test
    void login_aSuccessDoesNotEraseTheFailuresOfTheSameIp() throws Exception {
        String ip = "192.0.2.15";
        String owner = createAccount();
        try {
            for (int i = 0; i < 18; i++) {
                wrongAttempts(ip, uniqueEmail(), new MockHttpSession(), 1);
            }
            mockMvc.perform(attempt(ip, owner, ACCOUNT_PASSWORD, new MockHttpSession()))
                    .andExpect(redirectedUrl("/"))
                    .andExpect(authenticated());

            // still 18 counted failures: the 19th is checked, the 20th is checked and blocks, the 21st is refused
            wrongAttempts(ip, uniqueEmail(), new MockHttpSession(), 2);
            mockMvc.perform(attempt(ip, owner, ACCOUNT_PASSWORD, new MockHttpSession()))
                    .andExpect(redirectedUrl("/login?blocked"))
                    .andExpect(unauthenticated());
        } finally {
            deleteAccount(owner);
        }
    }

    /** AC-008-8: 20 lần sai từ một IP với nhiều email thì mọi lần sau từ IP đó bị chặn; IP khác thì không. */
    @Test
    void login_twentyWrongPasswordsFromOneIp_blockThatIpOnly() throws Exception {
        String ip = "192.0.2.16";
        for (int i = 0; i < 20; i++) {
            wrongAttempts(ip, uniqueEmail(), new MockHttpSession(), 1);
        }

        mockMvc.perform(attempt(ip, uniqueEmail(), "Wrong-pass-1", new MockHttpSession()))
                .andExpect(redirectedUrl("/login?blocked"))
                .andExpect(unauthenticated());
        wrongAttempts("192.0.2.17", uniqueEmail(), new MockHttpSession(), 1);
    }

    /** Cổng phải khớp với đường dẫn mà bộ lọc đăng nhập xử lý: không có cách viết URL nào lách được. */
    @Test
    void login_whenBlocked_theCorrectPasswordIsRefusedOnEveryWayToSpellTheLoginUrl() throws Exception {
        String email = createAccount();
        try {
            MockHttpSession session = new MockHttpSession();
            wrongAttempts("192.0.2.18", email, session, 5);
            mockMvc.perform(attempt("192.0.2.18", email, ACCOUNT_PASSWORD, session))
                    .andExpect(redirectedUrl("/login?blocked"));

            for (String path : new String[]{"/login/", "/login;x=1", "//login", "/login/%2e", "/LOGIN", "/login%2F"}) {
                MockHttpSession probe = new MockHttpSession();
                MvcResult result = mockMvc.perform(post("/login").session(probe).with(csrf())
                                .with(request -> {
                                    request.setRemoteAddr("192.0.2.19");
                                    request.setRequestURI(path);
                                    return request;
                                })
                                .param("email", email).param("password", ACCOUNT_PASSWORD))
                        .andExpect(unauthenticated())
                        .andReturn();
                assertThat(result.getResponse().getRedirectedUrl()).as("POST %s", path).isNotEqualTo("/");
            }
            // the account really is still blocked and was never logged in by one of the spellings above
            mockMvc.perform(attempt("192.0.2.19", email, ACCOUNT_PASSWORD, new MockHttpSession()))
                    .andExpect(redirectedUrl("/login?blocked"))
                    .andExpect(unauthenticated());
        } finally {
            deleteAccount(email);
        }
    }

    /** AC-008-10: bộ đếm bật ở context mặc định (PostgreSQL), đúng một bean, không có khóa cấu hình tắt nó. */
    @Test
    void context_hasExactlyOneLoginThrottleBean() {
        assertThat(context.getBeansOfType(LoginThrottle.class)).hasSize(1);
    }

    @Test
    void blockedPage_isPublicAndShowsOnlyTheBlockedNotice() throws Exception {
        mockMvc.perform(get("/login?blocked"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BLOCKED_TEXT)))
                .andExpect(content().string(not(containsString(WRONG_TEXT))));
    }
}
