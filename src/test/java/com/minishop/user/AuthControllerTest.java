package com.minishop.user;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Tầng web của đăng ký và đăng nhập, service được mock (AC-003-1, 2, 4, 6, 7, 8, 11, 17; BR-003-4). */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class})
class AuthControllerTest {

    private static final String SECRET = "Zx9SecretPw";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    /** AC-003-1 */
    @Test
    void registerForm_forAnonymous_rendersAllFields() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andExpect(content().string(containsString("<title>Đăng ký | Mini Shop</title>")))
                .andExpect(content().string(containsString("Họ và tên</label>")))
                .andExpect(content().string(containsString("Email</label>")))
                .andExpect(content().string(containsString("Mật khẩu</label>")))
                .andExpect(content().string(containsString("Nhập lại mật khẩu</label>")))
                .andExpect(content().string(containsString(">Đăng ký</button>")))
                .andExpect(content().string(containsString("Ít nhất 8 ký tự, gồm chữ và số.")))
                .andExpect(content().string(containsString("href=\"/login\">Đã có tài khoản? Đăng nhập</a>")));
    }

    /** AC-007-18: every text box of the register form carries the server's limit as maxlength. */
    @ParameterizedTest(name = "{0} has maxlength {1}")
    @CsvSource({"fullName,100", "email,255", "password,64", "confirmPassword,64"})
    void registerForm_inputs_carryMaxlengthEqualToServerLimit(String field, String maxlength) throws Exception {
        String html = mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher input = Pattern.compile("<input[^>]*\\bname=\"" + field + "\"[^>]*>").matcher(html);
        assertThat(input.find()).as("input %s in the register form", field).isTrue();
        assertThat(input.group()).contains("maxlength=\"" + maxlength + "\"");
    }

    /** AC-007-18: the server still validates when a request skips maxlength (101-character name). */
    @Test
    void register_withNameOf101Characters_showsSameErrorAsBefore() throws Exception {
        String html = mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "a".repeat(101))
                        .param("email", "an@example.vn")
                        .param("password", "matkhau123")
                        .param("confirmPassword", "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andExpect(model().attributeHasFieldErrors("form", "fullName"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Họ và tên tối đa 100 ký tự.");
        verify(userService, never()).register(any());
    }

    /** AC-007-18: a 256-character email is refused by the server even though the browser would stop at 255. */
    @Test
    void register_withEmailOf256Characters_showsLengthError() throws Exception {
        String longEmail = "a".repeat(256 - "@example.vn".length()) + "@example.vn";

        mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Nguyễn Văn An")
                        .param("email", longEmail)
                        .param("password", "matkhau123")
                        .param("confirmPassword", "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "email"));

        verify(userService, never()).register(any());
    }

    /** AC-003-8 */
    @Test
    void loginPage_forAnonymous_rendersFormAndAnonymousHeader() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/login"))
                .andExpect(content().string(containsString("<title>Đăng nhập | Mini Shop</title>")))
                .andExpect(content().string(containsString("Email</label>")))
                .andExpect(content().string(containsString("Mật khẩu</label>")))
                .andExpect(content().string(containsString(">Đăng nhập</button>")))
                .andExpect(content().string(containsString("href=\"/register\">Chưa có tài khoản? Đăng ký</a>")))
                .andExpect(content().string(containsString("href=\"/login\">Đăng nhập</a>")))
                .andExpect(content().string(containsString("href=\"/register\">Đăng ký</a>")))
                .andExpect(content().string(not(containsString("Quản trị"))))
                .andExpect(content().string(not(containsString("Đăng xuất"))))
                .andExpect(content().string(not(containsString("Đăng ký thành công"))))
                .andExpect(content().string(not(containsString("không đúng"))));
    }

    /** AC-003-11 */
    @Test
    void loginPage_withErrorAndFlashEmail_showsGenericMessageAndKeepsEmail() throws Exception {
        mockMvc.perform(get("/login?error").flashAttr("email", "a@b.vn"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email hoặc mật khẩu không đúng.")))
                .andExpect(content().string(containsString("value=\"a@b.vn\"")));
    }

    /** AC-003-2 */
    @Test
    void loginPage_withRegisteredFlash_showsSuccessNotice() throws Exception {
        mockMvc.perform(get("/login").flashAttr("registered", true))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đăng ký thành công. Vui lòng đăng nhập.")));
    }

    /** AC-003-2 */
    @Test
    void register_withValidForm_redirectsToLoginWithFlashAndCallsServiceOnce() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Nguyễn Văn An")
                        .param("email", "an@example.vn")
                        .param("password", "matkhau123")
                        .param("confirmPassword", "matkhau123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("registered", true));

        verify(userService).register(any(RegistrationForm.class));
    }

    /** AC-003-4 */
    @Test
    void register_whenEmailAlreadyRegistered_showsFieldErrorKeepsInputAndNeverEchoesPassword() throws Exception {
        when(userService.register(any())).thenThrow(new EmailAlreadyRegisteredException());

        mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Nguyễn Văn An")
                        .param("email", "an@example.vn")
                        .param("password", SECRET)
                        .param("confirmPassword", SECRET))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andExpect(model().attributeHasFieldErrors("form", "email"))
                .andExpect(content().string(containsString("Email này đã được đăng ký.")))
                .andExpect(content().string(containsString("value=\"Nguyễn Văn An\"")))
                .andExpect(content().string(containsString("value=\"an@example.vn\"")))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    /** AC-003-6, BR-003-4 */
    @Test
    void register_withInvalidData_showsEachErrorAndNeverEchoesPassword() throws Exception {
        String html = mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "")
                        .param("email", "khong-phai-email")
                        .param("password", "abc")
                        .param("confirmPassword", SECRET))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andExpect(model().attributeHasFieldErrors("form", "fullName", "email", "password",
                        "confirmPassword"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Vui lòng nhập họ và tên.")
                .contains("Email không hợp lệ.")
                .contains("Mật khẩu phải có từ 8 đến 64 ký tự.")
                .contains("Mật khẩu nhập lại không khớp.")
                .doesNotContain(SECRET)
                .doesNotContain("value=\"abc\"");
        verifyNoInteractions(userService);
    }

    /** AC-003-6, BR-003-4: hai mật khẩu hợp lệ nhưng ô khác sai vẫn không được điền lại. */
    @Test
    void register_withInvalidNameAndValidPasswords_doesNotEchoPasswords() throws Exception {
        String html = mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "")
                        .param("email", "an@example.vn")
                        .param("password", SECRET)
                        .param("confirmPassword", SECRET))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Vui lòng nhập họ và tên.").doesNotContain(SECRET);
        verify(userService, never()).register(any());
    }

    /** AC-003-7: form không có role/id nên tham số lạ bị bỏ qua, service chỉ nhận 4 trường. */
    @Test
    void register_withExtraRoleAndIdParams_ignoresThem() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Nguyễn Văn An")
                        .param("email", "an@example.vn")
                        .param("password", "matkhau123")
                        .param("confirmPassword", "matkhau123")
                        .param("role", "ADMIN")
                        .param("id", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        ArgumentCaptor<RegistrationForm> captor = ArgumentCaptor.forClass(RegistrationForm.class);
        verify(userService).register(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new RegistrationForm("Nguyễn Văn An", "an@example.vn", "matkhau123", "matkhau123"));
        assertThat(RegistrationForm.class.getRecordComponents()).extracting("name")
                .containsExactly("fullName", "email", "password", "confirmPassword");
    }

    /** AC-003-17 */
    @Test
    void loginAndRegisterPages_whenLoggedIn_redirectToHome() throws Exception {
        mockMvc.perform(get("/login").with(user(TestUsers.user())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/register").with(user(TestUsers.user())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ---------- GĐ8: dòng nhắc bản demo và thông báo bị chặn (AC-008-7, 19) ----------

    private static final String DEMO_NOTICE = "Đây là bản demo: vui lòng không nhập thông tin cá nhân thật.";
    private static final String BLOCKED_NOTICE = "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.";
    private static final String WRONG_NOTICE = "Email hoặc mật khẩu không đúng.";

    private static int occurrences(String html, String text) {
        int count = 0;
        for (int at = html.indexOf(text); at >= 0; at = html.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    private String page(MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** AC-008-19 */
    @Test
    void registerForm_showsTheDemoNoticeExactlyOnce() throws Exception {
        String html = page(get("/register"));

        assertThat(occurrences(html, DEMO_NOTICE)).isEqualTo(1);
        assertThat(html).contains("<p class=\"demo-notice\">" + DEMO_NOTICE + "</p>");
        // right under the heading, before the form
        assertThat(html.indexOf("<h1")).isLessThan(html.indexOf(DEMO_NOTICE));
        assertThat(html.indexOf(DEMO_NOTICE)).isLessThan(html.indexOf("<form method=\"post\" action=\"/register\""));
    }

    /** AC-008-19: the notice stays when the form is shown again with errors. */
    @Test
    void register_withInvalidForm_stillShowsTheDemoNoticeExactlyOnce() throws Exception {
        String html = mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "").param("email", "khong-hop-le").param("password", "1")
                        .param("confirmPassword", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andExpect(model().hasErrors())
                .andReturn().getResponse().getContentAsString();

        assertThat(occurrences(html, DEMO_NOTICE)).isEqualTo(1);
    }

    /** AC-008-19: also on the other way the form is shown again (a taken email). */
    @Test
    void register_whenEmailAlreadyRegistered_stillShowsTheDemoNoticeExactlyOnce() throws Exception {
        when(userService.register(any())).thenThrow(new EmailAlreadyRegisteredException());

        String html = mockMvc.perform(post("/register").with(csrf())
                        .param("fullName", "Nguyễn Văn An").param("email", "an@example.vn")
                        .param("password", "matkhau123").param("confirmPassword", "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/register"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Email này đã được đăng ký.");
        assertThat(occurrences(html, DEMO_NOTICE)).isEqualTo(1);
    }

    /** AC-008-19: the notice belongs to the register form and checkout only, never to the login page. */
    @Test
    void loginPages_neverShowTheDemoNotice() throws Exception {
        assertThat(page(get("/login"))).doesNotContain(DEMO_NOTICE).doesNotContain("demo-notice");
        assertThat(page(get("/login?error"))).doesNotContain(DEMO_NOTICE);
        assertThat(page(get("/login?blocked"))).doesNotContain(DEMO_NOTICE);
    }

    /** AC-008-7 */
    @Test
    void loginPage_withBlocked_showsTheBlockedNoticeAsAnAlertAndNotTheWrongPasswordOne() throws Exception {
        String html = page(get("/login?blocked"));

        assertThat(occurrences(html, BLOCKED_NOTICE)).isEqualTo(1);
        assertThat(html).doesNotContain(WRONG_NOTICE).doesNotContain("Đăng ký thành công");
        assertThat(Pattern.compile("<div[^>]*role=\"alert\"[^>]*>\\s*" + Pattern.quote(BLOCKED_NOTICE) + "\\s*</div>")
                .matcher(html).find()).as("an error notice with role=alert").isTrue();
    }

    /** AC-008-7: the typed email survives the blocked redirect, like it does after a wrong password. */
    @Test
    void loginPage_withBlockedAndFlashEmail_keepsTheTypedEmail() throws Exception {
        String html = page(get("/login?blocked").flashAttr("email", "a@b.vn"));

        assertThat(html).contains(BLOCKED_NOTICE).contains("value=\"a@b.vn\"");
    }

    /** AC-008-7: the two notices never show together, and the plain error page has no blocked text. */
    @Test
    void loginPage_withError_doesNotShowTheBlockedNotice() throws Exception {
        String html = page(get("/login?error"));

        assertThat(html).contains(WRONG_NOTICE).doesNotContain(BLOCKED_NOTICE);
    }

    /** AC-003-17 */
    @Test
    void registerPost_whenLoggedIn_redirectsToHomeWithoutCallingService() throws Exception {
        mockMvc.perform(post("/register").with(user(TestUsers.user())).with(csrf())
                        .param("fullName", "Nguyễn Văn An")
                        .param("email", "an@example.vn")
                        .param("password", "matkhau123")
                        .param("confirmPassword", "matkhau123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        verifyNoInteractions(userService);
    }
}
