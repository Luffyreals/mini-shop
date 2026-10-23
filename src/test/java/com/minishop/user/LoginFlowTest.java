package com.minishop.user;

import com.minishop.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
 * Luồng đăng nhập/đăng xuất đầu-cuối với tài khoản seed trên PostgreSQL thật. Chỉ đọc, không ghi DB.
 * Không dùng @MockitoSpyBean để dùng chung context với các test Testcontainers khác.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
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
}
